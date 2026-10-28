package com.vyzorix.bridge.apis

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.BridgeErrorKind
import org.json.JSONObject

/**
 * Connectivity state. No runtime permission.
 */
object NetworkApi : ApiHandler {

    const val NAME = "network"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val manager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no connectivity service")

        val network = manager.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }

        val transports =
            buildList {
                if (capabilities == null) return@buildList
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
                if (capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_CELLULAR,
                    )
                ) {
                    add("cellular")
                }
                if (capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_ETHERNET,
                    )
                ) {
                    add("ethernet")
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            }

        return ApiOutcome.ok(
            JSONObject()
                .put("connected", capabilities != null)
                .put("transports", transports.joinToString(","))
                .put(
                    "validated",
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        ?: false,
                ),
        )
    }
}
