package com.vyzorix.bridge.apis

import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * How long the screen stays on before it sleeps. A flasher that drives a screen for
 * a while needs to know this, or it will be talking to a dark device. Read-only; a
 * device owner can set it, a normal app cannot. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object ScreenTimeoutApi : ApiHandler {

    const val NAME = "screen_timeout"

    override fun handle(call: ApiCall): ApiOutcome {
        val resolver = call.context.contentResolver

        val timeout =
            try {
                Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT)
            } catch (e: Settings.SettingNotFoundException) {
                // The setting is absent, which is a fact rather than a failure. The
                // platform's message is carried so "not supported" and "not readable"
                // are distinguishable in a report.
                return ApiOutcome.ok(
                    JSONObject()
                        .put("known", false)
                        .put("timeoutMs", 0)
                        .put("detail", e.message.orEmpty()),
                )
            }

        return ApiOutcome.ok(JSONObject().put("known", true).put("timeoutMs", timeout))
    }
}
