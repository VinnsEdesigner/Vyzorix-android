package com.vyzorix.bridge.apis

import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Screen brightness and whether it is on automatic. Read from Settings.System,
 * which needs no permission to read. Writing it would need a system app, so this is
 * read-only by design. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object BrightnessApi : ApiHandler {

    const val NAME = "brightness"

    override fun handle(call: ApiCall): ApiOutcome {
        val resolver = call.context.contentResolver

        val manual = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 0)
        val automatic = Settings.System.getInt(resolver, SCREEN_BRIGHTNESS_MODE, 0) != 0

        return ApiOutcome.ok(
            JSONObject()
                .put("brightness", manual)
                .put("maxBrightness", MAX_BRIGHTNESS)
                .put("automatic", automatic),
        )
    }

    /** The platform's brightness range: 0..255. */
    private const val MAX_BRIGHTNESS = 255
    private const val SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode"
}
