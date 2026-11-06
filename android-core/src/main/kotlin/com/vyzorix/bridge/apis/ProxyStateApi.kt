package com.vyzorix.bridge.apis

import android.content.Context
import android.net.ConnectivityManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Whether an HTTP proxy is configured for the app process. A proxy silently breaks
 * every outbound request the container makes, and the failure looks like the
 * network being down. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object ProxyStateApi : ApiHandler {

    const val NAME = "proxy_state"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no connectivity service",
                )

        val properties = manager.activeNetwork?.let { manager.getLinkProperties(it) }
        val proxy = properties?.httpProxy

        return ApiOutcome.ok(
            JSONObject()
                .put("configured", proxy != null)
                .put("host", proxy?.host.orEmpty())
                .put("port", proxy?.port ?: 0)
                .put("exclusionList", proxy?.exclusionList?.joinToString(",").orEmpty()),
        )
    }
}
