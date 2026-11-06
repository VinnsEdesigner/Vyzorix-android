package com.vyzorix.bridge.apis

import android.app.KeyguardManager
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
 * Whether the screen is on and whether the device is locked. What a flasher needs
 * to know before it drives a screen. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object ScreenStateApi : ApiHandler {

    const val NAME = "screen_state"

    override fun handle(call: ApiCall): ApiOutcome {
        val power =
            call.context.getSystemService(Context.POWER_SERVICE)
                as? PowerManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no power service",
                )

        val keyguard =
            call.context.getSystemService(Context.KEYGUARD_SERVICE)
                as? KeyguardManager

        return ApiOutcome.ok(
            JSONObject()
                .put("interactive", power.isInteractive)
                .put("screenOn", power.isScreenOn)
                .put("locked", keyguard?.isKeyguardLocked ?: false)
                .put("secure", keyguard?.isKeyguardSecure ?: false),
        )
    }
}
