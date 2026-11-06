package com.vyzorix.bridge.apis

import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which accessibility services are enabled. An operator driving the screen needs to
 * know whether a screen reader is intercepting input. Read-only. No runtime
 * permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object AccessibilityApi : ApiHandler {

    const val NAME = "accessibility"

    override fun handle(call: ApiCall): ApiOutcome {
        val raw =
            Settings.Secure.getString(call.context.contentResolver, ENABLED_SERVICES).orEmpty()

        val services =
            if (raw.isEmpty()) {
                emptyList()
            } else {
                raw
                    .split(
                        SEPARATOR,
                    ).filter { it.isNotEmpty() }
            }

        return ApiOutcome.ok(
            JSONObject().put("enabled", JSONArray(services)).put("count", services.size),
        )
    }

    private const val ENABLED_SERVICES = "enabled_accessibility_services"
    private const val SEPARATOR = ':'
}
