package com.vyzorix.connector.command

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.vyzorix.connector.cloud.CloudJson
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Runs a command against the the Vyzorix CLI supervisor's control socket.
 *
 * The supervisor lives inside the container and speaks the repo's framed protocol
 * (`[4B length][1B type][payload]`, JSON payload) over a unix socket at
 * `/home/apps/supervisor.sock` — which is `files/apps/supervisor.sock` on the host,
 * because that is what the proot launcher binds to `/home/apps`.
 *
 * ## Why this is a client, not a call into the supervisor
 *
 * The supervisor is a separate process inside the container, and the connector runs
 * in the app process. There is no shared memory between them; the socket is the
 * whole interface, and it is the same one the `app-*` CLI uses. Reaching into the
 * supervisor any other way would mean the connector and the CLI disagreed about the
 * protocol, which is exactly the drift the CLI's design avoids.
 *
 * ## Why it returns a failure rather than throwing
 *
 * A missing socket is a normal state, not a fault: the supervisor may not be
 * installed (an APK built without the toolchain), may be restarting, or the container
 * may be absent. The user needs to be told which, and `CommandOutcome.failed`
 * carries it. Throwing would be caught by the dispatcher and reported as a crash,
 * which hides the reason.
 */
class AppControlClient(
    /** The control socket on the host filesystem. */
    private val socketPath: File,
    /** How long a call may take before it is reported as a timeout. */
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    /**
     * Sends one request and returns the supervisor's `result`, or a failure.
     *
     * `args` is a pre-encoded JSON object body, because the caller knows its own
     * arguments and building them twice would be a second place to get the escaping
     * wrong.
     */
    fun call(
        op: String,
        args: Map<String, String> = emptyMap(),
    ): CommandOutcome {
        val request = buildRequest(op, args)

        val socket = LocalSocket()
        try {
            // FILESYSTEM, not the abstract namespace: the supervisor binds a
            // filesystem path so proot can bind it into the container, which an
            // abstract name could not be.
            val address =
                LocalSocketAddress(
                    socketPath.absolutePath,
                    LocalSocketAddress.Namespace.FILESYSTEM,
                )
            socket.connect(address)
        } catch (e: IOException) {
            // The socket is absent or nothing is listening. Both mean the same thing
            // to a user: the app runtime is not available.
            runCatching { socket.close() }

            return CommandOutcome.failed(
                "cannot reach the app runtime at $socketPath: ${e.message}",
            )
        }

        return try {
            exchange(socket, request)
        } catch (e: IOException) {
            CommandOutcome.failed("app runtime call failed: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun exchange(
        socket: LocalSocket,
        request: String,
    ): CommandOutcome {
        val body = request.toByteArray(Charsets.UTF_8)

        // A blocking read with no deadline would hang the dispatch thread if the
        // supervisor died mid-call, so the read is bounded.
        socket.soTimeout = timeoutMs

        val out: OutputStream = socket.outputStream
        val header = java.nio.ByteBuffer.allocate(HEADER_SIZE)
        header.putInt(body.size)
        header.put(FRAME_REQUEST)
        writeAll(out, header.array())
        writeAll(out, body)

        val input: InputStream = socket.inputStream

        val replyHeader = readFully(input, HEADER_SIZE)
        if (replyHeader == null) {
            return CommandOutcome.failed("the app runtime closed the connection without replying")
        }
        val length = ByteBuffer.wrap(replyHeader).int
        if (length < 0 || length > MAX_PAYLOAD) {
            return CommandOutcome.failed("the app runtime sent an oversized reply ($length)")
        }

        val payload = readFully(input, length)
        if (payload == null) {
            return CommandOutcome.failed("the app runtime closed the connection mid-reply")
        }

        return parseReply(String(payload, Charsets.UTF_8))
    }

    /**
     * Turns the supervisor's `{"ok":bool,"result":…|"error":…}` into an outcome.
     *
     * A malformed reply is a failure with the text, not an exception: the user
     * needs to see what came back, and a supervisor built from a different revision
     * is a real possibility.
     */
    private fun parseReply(reply: String): CommandOutcome {
        val parsed =
            try {
                CloudJson.json.parseToJsonElement(reply).jsonObject
            } catch (e: Exception) {
                // The reply is included: a supervisor from a different revision is a
                // real possibility, and its text is the only clue.
                return CommandOutcome.failed(
                    "unreadable reply from the app runtime: $reply (${e.message})",
                )
            }

        val ok = (parsed["ok"] as? JsonPrimitive)?.content?.toBoolean() ?: false
        if (!ok) {
            val error = (parsed["error"] as? JsonPrimitive)?.content ?: "unknown error"

            return CommandOutcome.failed(error)
        }

        // The result is either a string (start/stop/restart/logs) or an object
        // (list, env). Both are reported as text: the user reads it, and the
        // command-result path carries a string.
        val result = parsed["result"]
        val text =
            when (result) {
                is JsonPrimitive -> result.content
                null -> ""
                else -> result.toString()
            }

        return CommandOutcome.ok(text)
    }

    private fun buildRequest(
        op: String,
        args: Map<String, String>,
    ): String {
        val fields = StringBuilder("{\"op\":\"").append(escape(op)).append('"')
        for ((key, value) in args) {
            fields.append(",\"").append(escape(key))
            fields.append("\":\"").append(escape(value)).append('"')
        }
        fields.append('}')

        return fields.toString()
    }

    /** JSON string escaping, matching what the supervisor's reader accepts. */
    private fun escape(raw: String): String {
        val out = StringBuilder(raw.length + 8)
        for (c in raw) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }

        return out.toString()
    }

    private fun writeAll(
        out: OutputStream,
        bytes: ByteArray,
    ) {
        out.write(bytes)
        out.flush()
    }

    /** Reads exactly `size` bytes, or null at EOF. */
    private fun readFully(
        input: InputStream,
        size: Int,
    ): ByteArray? {
        if (size == 0) return ByteArray(0)

        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) return null
            read += n
        }

        return buffer
    }

    private companion object {
        const val HEADER_SIZE = 5
        const val FRAME_REQUEST: Byte = 0x01
        const val MAX_PAYLOAD = 1 shl 20
        const val DEFAULT_TIMEOUT_MS = 10_000
    }
}
