package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import java.net.NetworkInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * The network interfaces and their addresses. Where `network` reports connectivity
 * at the platform level, this reports what the kernel has — which is what an
 * user needs to reach the device on a hotspot. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object NetworkInterfacesApi : ApiHandler {

    const val NAME = "network_interfaces"

    override fun handle(call: ApiCall): ApiOutcome {
        val array = JSONArray()

        // getNetworkInterfaces() returns an Enumeration, not an Iterable: there is no
        // orEmpty() and no for-in, so it is walked with hasMoreElements.
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces != null && interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()

            val addresses = JSONArray()
            for (address in iface.inetAddresses) {
                addresses.put(
                    JSONObject()
                        .put("address", address.hostAddress.orEmpty())
                        .put("loopback", address.isLoopbackAddress)
                        .put("siteLocal", address.isSiteLocalAddress),
                )
            }

            array.put(
                JSONObject()
                    .put("name", iface.name)
                    .put("up", iface.isUp)
                    .put("loopback", iface.isLoopback)
                    .put("virtual", iface.isVirtual)
                    .put("mtu", iface.mtu)
                    .put("addresses", addresses),
            )
        }

        return ApiOutcome.ok(JSONObject().put("interfaces", array).put("count", array.length()))
    }
}
