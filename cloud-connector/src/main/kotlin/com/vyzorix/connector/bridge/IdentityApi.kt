package com.vyzorix.connector.bridge

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Exposes the device's own identity over the bridge: its uuid and its public key.
 *
 * The container may need to prove it is running on this device — for example when
 * it talks to a service that pins the device key. Only the **public** half is
 * exposed; the private key never leaves the Keystore, and this API has no way to
 * reach it.
 *
 * It is a handler class rather than an `object` because the identity is resolved
 * lazily from the connector, which the service constructs.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
class IdentityApi(
    private val uuidProvider: () -> String,
    private val publicKeyProvider: () -> String,
) : ApiHandler {
    override fun handle(call: ApiCall): ApiOutcome =
        ApiOutcome.ok(
            JSONObject()
                .put("deviceUuid", uuidProvider())
                .put("publicKey", publicKeyProvider()),
        )

    companion object {
        const val NAME = "identity"
    }
}
