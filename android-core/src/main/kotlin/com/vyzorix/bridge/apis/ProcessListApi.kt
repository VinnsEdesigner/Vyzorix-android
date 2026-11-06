package com.vyzorix.bridge.apis

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * The processes the app is allowed to see. From Android 8 that is normally only its
 * own, which is reported honestly rather than presented as the whole device — a
 * caller that assumed otherwise would be wrong. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object ProcessListApi : ApiHandler {

    const val NAME = "process_list"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no activity service",
                )

        val running = manager.runningAppProcesses.orEmpty()
        val array = JSONArray()
        for (process in running) {
            array.put(
                JSONObject()
                    .put("pid", process.pid)
                    .put("processName", process.processName)
                    .put("importance", process.importance)
                    .put("own", process.pid == Process.myPid()),
            )
        }

        return ApiOutcome.ok(
            JSONObject()
                .put("processes", array)
                .put("count", array.length())
                .put("visibilityLimited", true),
        )
    }
}
