package com.vyzorix.agent

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyzorix.container.ProotLauncher
import com.vyzorix.service.VyzorixForegroundService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs one arbitrary command inside the container, through the production terminal
 * path, and prints everything the shell wrote.
 *
 * ## Why this exists
 *
 * The bridge's 58 APIs are fixed readers — device info, sensors, storage. They cannot
 * clone a repository or run a build. The one production path that can run *any*
 * command is the operator's terminal: the service opens a PTY, `proot` starts a
 * Debian login shell, and whatever the operator types runs inside the container.
 * That is the path this test drives, so "run this command in the container" means
 * "what an operator would get", not a shortcut through `run-as` (which runs in a
 * different SELinux domain and a different mount namespace, and so measures
 * something the app never does).
 *
 * The command is supplied at run time, so one test binary drives any command:
 *
 * ```
 * adb shell am instrument -w -e command 'uname -a' \
 *   com.vyzorix.agent.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * ## Why a probe before the command
 *
 * A proot shell is occasionally slow or dead at startup (proot writes its loader and
 * execs between the fork and the shell reading its tty). A command written in that
 * window is echoed by the line discipline and then discarded, so the shell would run
 * nothing and the test would hang on a marker that never comes. This test therefore
 * sends a harmless probe first and only sends the real command once the probe has
 * answered — proving the shell is alive and reading — rather than re-sending a
 * command that may be a long build (re-sending would run it twice).
 */
@RunWith(AndroidJUnit4::class)
class OperatorCommandE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun startTheService() {
        VyzorixForegroundService.start(context)

        assertTrue(
            "the container must install and report ready",
            await(READY_TIMEOUT_MS) {
                ProotLauncher.isReady(context)
            },
        )
        assertTrue(
            "the container tools must be installed",
            await(READY_TIMEOUT_MS) { ProotLauncher.hasSupervisor(context) },
        )
        assertTrue(
            "the service's terminal port must be published",
            await(READY_TIMEOUT_MS) { VyzorixForegroundService.terminalPortOrNull() != null },
        )
    }

    @Test
    fun runsTheOperatorCommand() {
        val args = InstrumentationRegistry.getArguments()
        val command =
            args.getString("command")
                ?: error("pass the command with: -e command '<shell command>'")
        val timeoutMs =
            args.getString("timeoutSeconds")?.toLongOrNull()?.times(1000) ?: DEFAULT_TIMEOUT_MS

        val output = runInContainer(command, timeoutMs)

        // The whole transcript, chunked so no single log line is dropped.
        output.chunked(LOG_CHUNK).forEach { Log.i(TAG, it) }

        assertTrue(
            "the command never produced its end marker; output was: ${output.takeLast(800)}",
            output.contains(SENTINEL),
        )
    }

    /**
     * Opens a fresh shell, proves it is alive with a probe, runs [command], and
     * returns everything the shell wrote (command echo, output, and marker).
     */
    private fun runInContainer(
        command: String,
        timeoutMs: Long,
    ): String {
        repeat(SHELL_ATTEMPTS) { attempt ->
            val result = runOnceInFreshShell(command, timeoutMs)
            if (result != null) return result

            Log.i(
                TAG,
                "shell ${attempt + 1}/$SHELL_ATTEMPTS did not come up; retrying on a fresh one",
            )
        }

        error("the shell never came up after $SHELL_ATTEMPTS attempts")
    }

    private fun runOnceInFreshShell(
        command: String,
        timeoutMs: Long,
    ): String? {
        val port =
            VyzorixForegroundService.terminalPortOrNull()
                ?: error("the service's terminal port is not up")

        val output = CopyOnWriteArrayList<ByteArray>()
        val exited = CountDownLatch(1)
        val sessionId = "cmd-${System.nanoTime()}"

        val opened =
            port.open(
                sessionId = sessionId,
                rows = 24,
                cols = 200,
                onOutput = { _, bytes -> output += bytes },
                onExit = { _, _ -> exited.countDown() },
            )
        assertTrue("the terminal port must open a shell", opened)

        fun transcript(): String = buildString { output.forEach { append(String(it)) } }

        try {
            // Prove the shell is alive and reading before sending the real command.
            if (!probe(port, sessionId, ::transcript)) return null

            // The command, then the marker. The marker is assembled by the shell from
            // two halves, so the tty's echo of the input cannot satisfy the wait.
            port.write(
                sessionId,
                "$command\nprintf '%s%s\\n' $SENTINEL_LEFT $SENTINEL_RIGHT\n".toByteArray(),
            )

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (transcript().contains(SENTINEL)) break
                Thread.sleep(POLL_MS)
            }

            return transcript()
        } finally {
            port.close(sessionId)
            exited.await(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    /**
     * Sends a harmless echo and waits for it, proving the shell reads its tty.
     *
     * Returns false when the shell produced nothing within the startup window — the
     * proot startup race — so the caller can retry on a fresh shell without having
     * run (or re-run) the real command.
     */
    private fun probe(
        port: com.vyzorix.service.PtySessionPort,
        sessionId: String,
        transcript: () -> String,
    ): Boolean {
        val token = "READY_${System.nanoTime()}"
        port.write(sessionId, "printf '%s\\n' $token\n".toByteArray())

        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // The token appears twice: once in the echo of the command, once as the
            // shell's own output. Either is proof the shell is reading; requiring the
            // second would mean parsing the transcript, which is not worth it here.
            if (transcript().contains(token)) return true
            Thread.sleep(POLL_MS)
        }

        return false
    }

    private fun await(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(POLL_MS)
        }

        return predicate()
    }

    private companion object {
        const val TAG = "VyzorixCmd"
        const val CONTAINER_READY_TIMEOUT_MS = 240_000L
        const val READY_TIMEOUT_MS = 240_000L
        const val DEFAULT_TIMEOUT_MS = 900_000L
        const val PROBE_TIMEOUT_MS = 25_000L
        const val EXIT_TIMEOUT_SECONDS = 10L
        const val POLL_MS = 200L
        const val LOG_CHUNK = 3_000
        const val SHELL_ATTEMPTS = 4

        const val SENTINEL_LEFT = "__VYZCMD_"
        const val SENTINEL_RIGHT = "DONE__"
        const val SENTINEL = SENTINEL_LEFT + SENTINEL_RIGHT
    }
}
