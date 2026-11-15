package com.vyzorix.bridge.apis

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Screen geometry and density. What a proxied web UI needs to render at the
 * right size, and what a user needs to know a device is a phone rather than
 * a tablet. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object DisplayInfoApi : ApiHandler {

    const val NAME = "display_info"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no window service",
                )

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)

        return ApiOutcome.ok(
            JSONObject()
                .put("widthPx", metrics.widthPixels)
                .put("heightPx", metrics.heightPixels)
                .put("densityDpi", metrics.densityDpi)
                .put("density", metrics.density.toDouble())
                .put("scaledDensity", metrics.scaledDensity.toDouble())
                .put("xdpi", metrics.xdpi.toDouble())
                .put("ydpi", metrics.ydpi.toDouble()),
        )
    }
}
