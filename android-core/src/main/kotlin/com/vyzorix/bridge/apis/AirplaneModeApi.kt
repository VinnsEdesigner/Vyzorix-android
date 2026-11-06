package com.vyzorix.bridge.apis

import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Whether airplane mode is on. Read from Settings.Global, which is world-readable;
 * writing it would need WRITE_SECURE_SETTINGS, so this is read-only by design.
 * No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object AirplaneModeApi : ApiHandler {

    const val NAME = "airplane_mode"

    override fun handle(call: ApiCall): ApiOutcome {
        val on =
            Settings.Global.getInt(call.context.contentResolver, AIRPLANE_MODE, OFF) != OFF

        return ApiOutcome.ok(JSONObject().put("airplaneMode", on))
    }

    /** The global setting; there is no public API that reports it. */
    private const val AIRPLANE_MODE = "airplane_mode_on"
    private const val OFF = 0
}
