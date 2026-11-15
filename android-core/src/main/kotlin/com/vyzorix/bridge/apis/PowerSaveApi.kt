package com.vyzorix.bridge.apis

import android.content.Context
import android.os.PowerManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Battery-saver and idle state. An user checking whether the device will keep
 * the service alive wants this, because battery saver is what Android uses to
 * throttle a background service. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object PowerSaveApi : ApiHandler {

    const val NAME = "power_save"

    override fun handle(call: ApiCall): ApiOutcome {
        val power =
            call.context.getSystemService(Context.POWER_SERVICE)
                as? PowerManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no power service",
                )

        // isDeviceIdleMode is API 23; the service's minSdk is 26, so no guard is needed
        // and adding one would be dead code.
        return ApiOutcome.ok(
            JSONObject()
                .put("powerSaveMode", power.isPowerSaveMode)
                .put("deviceIdle", power.isDeviceIdleMode)
                .put("interactive", power.isInteractive),
        )
    }
}
