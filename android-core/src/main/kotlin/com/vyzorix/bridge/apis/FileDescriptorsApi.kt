package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import java.io.File
import org.json.JSONObject

/**
 * How many file descriptors the app process has open, and its limit. A leaked
 * descriptor is the classic slow failure in this design — the PTY broker's whole
 * ownership rule exists to prevent it — so being able to watch the count is worth
 * an API. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object FileDescriptorsApi : ApiHandler {

    const val NAME = "file_descriptors"

    override fun handle(call: ApiCall): ApiOutcome {
        val fdDir = File("/proc/self/fd")
        val open =
            fdDir.list()?.size ?: return ApiOutcome.fail(
                BridgeErrorKind.INTERNAL,
                "/proc/self/fd is not readable",
            )

        val limit = readLimit()

        return ApiOutcome.ok(
            JSONObject()
                .put("open", open)
                .put("softLimit", limit.first)
                .put("hardLimit", limit.second),
        )
    }

    /** The soft and hard descriptor limits from `/proc/self/limits`. */
    private fun readLimit(): Pair<Long, Long> {
        val file = File("/proc/self/limits")
        if (!file.canRead()) return 0L to 0L

        for (line in file.readLines()) {
            if (!line.startsWith("Max open files")) continue

            val numbers = line.filter { it.isDigit() || it == ' ' }.trim().split(" ")

            return (numbers.getOrNull(0)?.toLongOrNull() ?: 0L) to
                (numbers.getOrNull(1)?.toLongOrNull() ?: 0L)
        }

        return 0L to 0L
    }
}
