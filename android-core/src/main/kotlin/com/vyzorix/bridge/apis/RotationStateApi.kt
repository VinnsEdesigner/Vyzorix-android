package com.vyzorix.bridge.apis

import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * The screen's rotation setting and whether auto-rotate is on. A flasher that
 * screenshots or drives a screen needs the orientation it is actually in. No
 * runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object RotationStateApi : ApiHandler {

    const val NAME = "rotation_state"

    override fun handle(call: ApiCall): ApiOutcome {
        val resolver = call.context.contentResolver

        val rotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0)
        val autoRotate = Settings.System.getInt(resolver, ACCELEROMETER_ROTATION, 0) != 0

        return ApiOutcome.ok(
            JSONObject().put("userRotation", rotation).put("autoRotate", autoRotate),
        )
    }

    /** `accelerometer_rotation`: 1 when auto-rotate is on. */
    private const val ACCELEROMETER_ROTATION = "accelerometer_rotation"
}
