package com.vyzorix.bridge.apis

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Whether the active network is a VPN. A VPN changes what the device can reach and
 * is a common reason a proxied app's requests behave unexpectedly. No runtime
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
object VpnStateApi : ApiHandler {

    const val NAME = "vpn_state"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no connectivity service",
                )

        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        val viaVpn =
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false

        return ApiOutcome.ok(
            JSONObject()
                .put("vpn", viaVpn)
                .put(
                    "validated",
                    capabilities?.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                    ) ?: false,
                ),
        )
    }
}
