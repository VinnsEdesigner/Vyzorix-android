package com.vyzorix.bridge.apis

import android.os.Build
import android.os.SystemClock
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Device identity and platform facts. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object DeviceInfoApi : ApiHandler {

    const val NAME = "device_info"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val fields =
            JSONObject()
                .put("model", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("apiLevel", Build.VERSION.SDK_INT)
                .put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))
                .put("uptimeMs", SystemClock.elapsedRealtime())
                .put("packageName", context.packageName)

        return ApiOutcome.ok(fields)
    }
}
