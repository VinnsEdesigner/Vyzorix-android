package com.vyzorix.connector.secret

import com.vyzorix.connector.cloud.CloudJson
import com.vyzorix.connector.cloud.DeviceRequestSigner
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The device-signed fetch of a secret's ciphertext: `GET /v1/device/{imei}/secrets/{app}/{key}`
 * (VYZORIX_DEPLOY_CONTRACT.md §9b.6). The server answers only for a key the current
 * signed config names, so a device cannot ask for a secret it was not given.
 *
 * The response carries the wrapped value and the envelope algorithm; the plaintext
 * is never on the wire.
 */
class HttpSecretTransport(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val deviceId: String,
    private val requestSigner: DeviceRequestSigner,
) : SecretTransport {
    private val json = CloudJson.json

    override fun ciphertext(
        app: String,
        key: String,
    ): WrappedSecret? {
        val path = "/v1/device/$deviceId/secrets/$app/$key"
        val request =
            Request
                .Builder()
                .url("$baseUrl$path")
                .get()
                .apply {
                    requestSigner.headers("GET", path, ByteArray(0)).forEach { (k, v) ->
                        header(k, v)
                    }
                }.build()

        http.newCall(request).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND) return null
            if (!response.isSuccessful) {
                throw IOException("secret fetch failed: HTTP ${response.code} for $app.$key")
            }

            val body = response.body?.string().orEmpty()
            val dto = json.decodeFromString(CiphertextDto.serializer(), body)
            if (dto.envelope != EXPECTED_ENVELOPE) {
                // The device only speaks RSA-OAEP-SHA1 (the Keystore's constraint), so
                // an unknown envelope is a refusal rather than a decryption attempt.
                throw IOException("unsupported secret envelope: ${dto.envelope}")
            }

            return WrappedSecret(dto.ciphertext, dto.envelope)
        }
    }

    @Serializable
    private data class CiphertextDto(
        val app: String = "",
        val key: String = "",
        val generation: Int = 0,
        val ciphertext: String = "",
        @SerialName("envelope") val envelope: String = "",
    )

    private companion object {
        const val HTTP_NOT_FOUND = 404

        /** The only envelope the device's Keystore key can open (§9b.3). */
        const val EXPECTED_ENVELOPE = "RSA-OAEP-SHA1"
    }
}
