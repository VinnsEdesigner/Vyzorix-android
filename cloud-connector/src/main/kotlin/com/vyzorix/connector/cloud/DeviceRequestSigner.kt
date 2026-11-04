package com.vyzorix.connector.cloud

import com.vyzorix.connector.identity.DeviceSigner
import java.security.MessageDigest
import java.util.Base64

/**
 * Signs a device HTTP request the way the server verifies it.
 *
 * The canonical string is [Signatures.deviceRequest], which mirrors the server's
 * `DeviceCanonicalString`. The three headers are the ones `requireHMAC` reads
 * (`X-Vyzorix-Timestamp` / `X-Vyzorix-Nonce` / `X-Vyzorix-Signature`); reusing
 * them keeps one scheme across the device surface.
 *
 * The signature covers the request body, so the body must be the exact bytes that
 * are sent. A JSON library that reorders keys between signing and sending would
 * produce a signature that does not verify — the caller signs the serialised bytes
 * it will transmit, never a re-encoding.
 */
class DeviceRequestSigner(
    private val signer: DeviceSigner,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonceFactory: () -> String = ::randomNonce,
) {
    /** The three headers to attach to a request for [method] [path] with [body]. */
    fun headers(
        method: String,
        path: String,
        body: ByteArray,
    ): Map<String, String> {
        val timestamp = clock().toString()
        val nonce = nonceFactory()

        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body)
        val bodyHashB64 = Base64.getEncoder().encodeToString(bodyHash)

        val canonical =
            Signatures.deviceRequest(
                method,
                path,
                deviceId,
                timestamp,
                nonce,
                bodyHashB64,
            )
        val signature = signer.sign(canonical)

        return mapOf(
            HEADER_TIMESTAMP to timestamp,
            HEADER_NONCE to nonce,
            HEADER_SIGNATURE to signature,
        )
    }

    companion object {
        const val HEADER_TIMESTAMP = "X-Vyzorix-Timestamp"
        const val HEADER_NONCE = "X-Vyzorix-Nonce"
        const val HEADER_SIGNATURE = "X-Vyzorix-Signature"

        /** 128 bits of randomness, hex. The server's nonce cache keys on device+nonce. */
        fun randomNonce(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)

            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
