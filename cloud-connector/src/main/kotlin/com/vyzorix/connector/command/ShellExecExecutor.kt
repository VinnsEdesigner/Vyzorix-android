package com.vyzorix.connector.command

import com.vyzorix.capability.PtyBroker
import com.vyzorix.capability.PtyReader
import com.vyzorix.capability.PtyRequest
import com.vyzorix.capability.PtyResult
import com.vyzorix.connector.cloud.CloudJson
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Runs a `SHELL_EXEC` command: a one-shot, stateless shell command whose output is
 * returned through the command-result path.
 *
 * It runs through the same PTY the interactive terminal uses, so a command sees the
 * same environment and the same container. It is deliberately **not** an
 * interactive session: it allocates a PTY, runs the command, waits for exit, and
 * returns the captured output. Nothing outlives the call.
 *
 * Output is bounded. A command that produces a `cat` of a large file would
 * otherwise grow the buffer without limit, and the result frame would be rejected
 * by the server's own size bound; capturing a fixed window and reporting truncation
 * is the honest behaviour.
 */
class ShellExecExecutor(
    private val broker: PtyBroker,
    private val shell: String = DEFAULT_SHELL,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) : CommandExecutor {
    override fun execute(args: String): CommandOutcome {
        val command =
            try {
                extractCommand(args)
            } catch (e: Exception) {
                return CommandOutcome.failed("could not read the command: ${e.message}")
            }

        if (command.isBlank()) {
            return CommandOutcome.failed("SHELL_EXEC requires a command")
        }

        val sessionId = "cmd-${UUID.randomUUID()}"

        // The PTY's stdio becomes the child's, so the shell runs the command with a
        // real tty and the output is what the user would have seen.
        val opened =
            broker.open(
                sessionId = sessionId,
                deviceId = ONE_SHOT_DEVICE,
                userId = ONE_SHOT_OPERATOR,
                request =
                    PtyRequest(
                        executable = shell,
                        // argv[0] must be the program name, not the first flag: the
                        // native launcher uses this list verbatim as argv, so a list
                        // starting with "-c" makes the shell treat "-c" as its own
                        // name and the command as the next argument — it ran the wrong
                        // thing. This mirrors ProotLauncher, whose argv starts with the
                        // binary path.
                        arguments = listOf(shell, "-c", command),
                        environment = listOf("TERM=dumb"),
                    ),
            )

        if (opened is PtyResult.Err) {
            return CommandOutcome.failed("could not start the command: ${opened.error.detail}")
        }

        val channel = (opened as PtyResult.Ok).value
        val session =
            broker.sessionForChannel(channel) ?: return CommandOutcome.failed("channel vanished")

        val capture = Capture(maxOutputBytes)
        val exited = CountDownLatch(1)
        val exitCode = intArrayOf(-1)

        val reader =
            PtyReader(
                session = session,
                onOutput = { bytes -> capture.write(bytes) },
                onExit = { code ->
                    exitCode[0] = code
                    exited.countDown()
                },
            )

        reader.start()

        // A command that never exits must not hold the dispatch thread forever.
        val finished = exited.await(timeoutMs, TimeUnit.MILLISECONDS)

        broker.close(channel)
        reader.awaitStopped(SHUTDOWN_WAIT_MS)

        val output = capture.text()

        return when {
            !finished -> CommandOutcome.failed("command timed out after ${timeoutMs}ms\n$output")
            exitCode[0] == 0 -> CommandOutcome.ok(output)
            else -> CommandOutcome.failed("command exited with ${exitCode[0]}\n$output")
        }
    }

    private fun extractCommand(args: String): String {
        val obj = CloudJson.json.parseToJsonElement(args).jsonObject

        // The server's SHELL_EXEC carries {"cmd": "..."} (or "command").
        return (obj["cmd"] as? JsonPrimitive)?.content
            ?: (obj["command"] as? JsonPrimitive)?.content
            ?: ""
    }

    /**
     * A bounded capture. The PTY reader writes from its own thread; the caller reads
     * after the exit latch, whose happens-before covers the read. The buffer is
     * synchronized anyway, because the reader may write once more before it observes
     * the stop signal.
     */
    private class Capture(
        private val maxBytes: Int,
    ) {
        private val buffer = ByteArrayOutputStream()
        private var truncated = false

        @Synchronized
        fun write(bytes: ByteArray) {
            val room = maxBytes - buffer.size()
            if (room <= 0) {
                truncated = true

                return
            }

            val take = minOf(room, bytes.size)
            buffer.write(bytes, 0, take)
            if (take < bytes.size) truncated = true
        }

        @Synchronized
        fun text(): String {
            val body = buffer.toString(Charsets.UTF_8.name())
            val suffix = if (truncated) "\n[output truncated at $maxBytes bytes]" else ""

            return body + suffix
        }
    }

    companion object {
        const val DEFAULT_SHELL = "/system/bin/sh"
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024
        private const val SHUTDOWN_WAIT_MS = 2_000L

        // A one-shot command is bound to no session; the PTY path does not use these.
        private const val ONE_SHOT_DEVICE = ""
        private const val ONE_SHOT_OPERATOR = ""
    }
}
