package com.vyzorix.bridge.apis

import android.content.ContentResolver
import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Whether haptic feedback and touch sounds are enabled. A UI driven remotely should
 * not buzz a phone in someone's pocket. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object HapticSettingsApi : ApiHandler {

    const val NAME = "haptic_settings"

    override fun handle(call: ApiCall): ApiOutcome {
        val resolver = call.context.contentResolver

        return ApiOutcome.ok(
            JSONObject()
                .put("hapticFeedback", isOn(resolver, HAPTIC_FEEDBACK))
                .put("touchSounds", isOn(resolver, SOUND_EFFECTS))
                .put("vibrateOnRing", isOn(resolver, VIBRATE_ON_RING)),
        )
    }

    private fun isOn(
        resolver: ContentResolver,
        key: String,
    ): Boolean = Settings.System.getInt(resolver, key, 0) != 0

    private const val HAPTIC_FEEDBACK = "haptic_feedback"
    private const val SOUND_EFFECTS = "sound_effects_enabled"
    private const val VIBRATE_ON_RING = "vibrate_when_ringing"
}
