package com.vyzorix.bridge.apis

import android.content.Context
import android.net.wifi.WifiManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Wi-Fi state. `ACCESS_WIFI_STATE` is a normal permission granted at install, so
 * there is no runtime dialog. The SSID is deliberately not reported: reading it
 * needs a location permission and it is a privacy leak, not a device fact.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object WifiStateApi : ApiHandler {

    const val NAME = "wifi_state"

    override fun handle(call: ApiCall): ApiOutcome {
        val wifi =
            call.context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no wifi service",
                )

        return ApiOutcome.ok(
            JSONObject()
                .put("enabled", wifi.isWifiEnabled)
                .put("state", wifi.wifiState)
                .put("scanAlwaysAvailable", wifi.isScanAlwaysAvailable),
        )
    }
}
