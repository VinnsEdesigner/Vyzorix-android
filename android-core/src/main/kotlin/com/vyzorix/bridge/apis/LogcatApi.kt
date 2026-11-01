package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recent logcat lines.
 *
 * `logcat -d` dumps the buffer and exits rather than following it, so this is a
 * bounded read with no persistent process. The line count and the output size are
 * both capped: a caller must not be able to pull an unbounded buffer through the
 * bridge, and logcat's default ring buffer is far larger than any response should
 * be.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PRIVATE,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object LogcatApi : ApiHandler {

    const val NAME = "logcat"

    private const val DEFAULT_LINES = 100
    private const val MAX_LINES = 500
    private const val MAX_OUTPUT_BYTES = 64 * 1024
    private const val TIMEOUT_SECONDS = 5L

    override fun handle(call: ApiCall): ApiOutcome {
        val requested = call.args["lines"]?.toIntOrNull() ?: DEFAULT_LINES
        if (requested <= 0) {
            return ApiOutcome.fail(BridgeErrorKind.INVALID_ARGS, "lines must be positive")
        }

        val lines = requested.coerceAtMost(MAX_LINES)

        return try {
            val process =
                ProcessBuilder("logcat", "-d", "-t", lines.toString())
                    .redirectErrorStream(true)
                    .start()

            try {
                // Drain while the process runs. Reading only after `waitFor` would
                // deadlock as soon as the output fills the pipe buffer: logcat
                // blocks on write, and we block on a process that cannot finish.
                // The read is bounded, so a runaway logcat cannot grow memory.
                val bytes = readBounded(process.inputStream, MAX_OUTPUT_BYTES + 1)

                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()

                    return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "logcat timed out")
                }

                val truncated = bytes.size > MAX_OUTPUT_BYTES
                val text = String(bytes.copyOf(minOf(bytes.size, MAX_OUTPUT_BYTES)), Charsets.UTF_8)

                ApiOutcome.ok(
                    JSONObject()
                        .put("lines", JSONArray(text.split('\n').filter { it.isNotEmpty() }))
                        .put("truncated", truncated),
                )
            } finally {
                process.destroy()
            }
        } catch (t: Throwable) {
            ApiOutcome.fail(BridgeErrorKind.INTERNAL, "logcat failed: ${t.message}")
        }
    }

    /** Reads at most [limit] bytes, returning early at end of stream. */
    private fun readBounded(
        input: java.io.InputStream,
        limit: Int,
    ): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val n = input.read(buffer, total, limit - total)
            if (n < 0) break
            total += n
        }

        return buffer.copyOf(total)
    }
}
