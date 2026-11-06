package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * The user's font scale and display size. A proxied web UI that ignores this
 * renders text at the wrong size for the person holding the phone. No runtime
 * permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object FontScaleApi : ApiHandler {

    const val NAME = "font_scale"

    override fun handle(call: ApiCall): ApiOutcome {
        val config = call.context.resources.configuration

        return ApiOutcome.ok(
            JSONObject()
                .put("fontScale", config.fontScale.toDouble())
                .put("densityDpi", config.densityDpi)
                .put("screenWidthDp", config.screenWidthDp)
                .put("screenHeightDp", config.screenHeightDp)
                .put("smallestWidthDp", config.smallestScreenWidthDp),
        )
    }
}
