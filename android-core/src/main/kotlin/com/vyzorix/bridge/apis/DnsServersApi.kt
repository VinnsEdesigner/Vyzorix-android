package com.vyzorix.bridge.apis

import android.content.Context
import android.net.ConnectivityManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * The DNS servers the active network is using. A container that cannot resolve
 * names is the single most common first-boot problem, so the resolvers it would
 * use are worth being able to read. No runtime permission beyond the network
 * state permission the registry declares.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object DnsServersApi : ApiHandler {

    const val NAME = "dns_servers"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no connectivity service",
                )

        val network = manager.activeNetwork
        val properties = network?.let { manager.getLinkProperties(it) }
        val servers = properties?.dnsServers.orEmpty().map { it.hostAddress.orEmpty() }

        return ApiOutcome.ok(
            JSONObject()
                .put("servers", JSONArray(servers))
                .put("count", servers.size)
                .put("interfaceName", properties?.interfaceName.orEmpty()),
        )
    }
}
