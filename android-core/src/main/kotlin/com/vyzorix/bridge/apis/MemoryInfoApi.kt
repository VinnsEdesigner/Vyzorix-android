package com.vyzorix.bridge.apis

import android.app.ActivityManager
import android.content.Context
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Process and device memory. The heap figures are the app's own; the device
 * figures are what an operator sizing an install needs. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object MemoryInfoApi : ApiHandler {

    const val NAME = "memory_info"

    override fun handle(call: ApiCall): ApiOutcome {
        val runtime = Runtime.getRuntime()
        val manager =
            call.context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no activity service",
                )

        val device = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(device)

        return ApiOutcome.ok(
            JSONObject()
                .put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("heapMaxBytes", runtime.maxMemory())
                .put("deviceTotalBytes", device.totalMem)
                .put("deviceAvailBytes", device.availMem)
                .put("lowMemory", device.lowMemory),
        )
    }
}
