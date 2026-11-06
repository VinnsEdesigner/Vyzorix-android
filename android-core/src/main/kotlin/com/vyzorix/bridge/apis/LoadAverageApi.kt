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
 * The kernel load average and the process count. A device under load is why a
 * command is slow, and this is the cheapest way to see it. `/proc/loadavg` is
 * world-readable. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object LoadAverageApi : ApiHandler {

    const val NAME = "load_average"

    override fun handle(call: ApiCall): ApiOutcome {
        val file = File("/proc/loadavg")
        if (!file.canRead()) {
            return ApiOutcome.fail(
                BridgeErrorKind.INTERNAL,
                "/proc/loadavg is not readable",
            )
        }

        // Five whitespace-separated fields: three load averages, then running/total, then
        // the most recent pid.
        val parts = file.readText().trim().split(" ")
        if (parts.size < PROCESS_FIELDS) {
            return ApiOutcome.fail(
                BridgeErrorKind.INTERNAL,
                "unexpected /proc/loadavg format",
            )
        }

        val processes = parts[3].split("/")

        return ApiOutcome.ok(
            JSONObject()
                .put("load1", parts[0].toDoubleOrNull() ?: 0.0)
                .put("load5", parts[1].toDoubleOrNull() ?: 0.0)
                .put("load15", parts[2].toDoubleOrNull() ?: 0.0)
                .put("running", processes.getOrNull(0)?.toIntOrNull() ?: 0)
                .put("total", processes.getOrNull(1)?.toIntOrNull() ?: 0),
        )
    }

    /** load1, load5, load15, running/total — four fields before the pid. */
    private const val PROCESS_FIELDS = 4
}
