package com.vyzorix.bridge.apis

import android.os.SystemClock
import android.text.format.DateFormat
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import java.util.TimeZone
import org.json.JSONObject

/**
 * Clocks: wall time, monotonic uptime, and the timezone. All three matter and
 * are different — an operator correlating a device event with a server log needs
 * wall time, and anything measuring a duration needs uptime. No runtime
 * permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object TimeInfoApi : ApiHandler {

    const val NAME = "time_info"

    override fun handle(call: ApiCall): ApiOutcome {
        val zone = TimeZone.getDefault()

        return ApiOutcome.ok(
            JSONObject()
                .put("epochMs", System.currentTimeMillis())
                .put("uptimeMs", SystemClock.elapsedRealtime())
                .put(
                    "uptimeDeepSleepMs",
                    SystemClock.elapsedRealtime() -
                        SystemClock.uptimeMillis(),
                ).put("timezoneId", zone.id)
                .put("timezoneOffsetMs", zone.getOffset(System.currentTimeMillis()))
                .put(
                    "use24Hour",
                    DateFormat
                        .is24HourFormat(call.context),
                ),
        )
    }
}
