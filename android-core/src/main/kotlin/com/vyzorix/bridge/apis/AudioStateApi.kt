package com.vyzorix.bridge.apis

import android.content.Context
import android.media.AudioManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Ringer mode and stream volumes. The volumes are useful to a proxied app that
 * plays sound; the ringer mode tells an operator whether the device will make
 * noise. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object AudioStateApi : ApiHandler {

    const val NAME = "audio_state"

    override fun handle(call: ApiCall): ApiOutcome {
        val audio =
            call.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no audio service",
                )

        val volumes = JSONObject()
        for ((label, stream) in STREAMS) {
            volumes.put(
                label,
                JSONObject()
                    .put("current", audio.getStreamVolume(stream))
                    .put("max", audio.getStreamMaxVolume(stream)),
            )
        }

        return ApiOutcome.ok(
            JSONObject()
                .put("ringerMode", audio.ringerMode)
                .put("isMusicActive", audio.isMusicActive)
                .put("isSpeakerphoneOn", audio.isSpeakerphoneOn)
                .put("volumes", volumes),
        )
    }

    /** The streams an operator can reason about, by the name a UI would use. */
    private val STREAMS =
        linkedMapOf(
            "music" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "alarm" to AudioManager.STREAM_ALARM,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "system" to AudioManager.STREAM_SYSTEM,
        )
}
