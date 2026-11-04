package com.vyzorix.connector.cloud

import com.vyzorix.connector.identity.DeviceSigner
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connect handshake: the device must build the URL the server verifies.
 *
 * The signature travels as query parameters (an upgrade cannot set headers) and is
 * **base64url**, because a `+` in a query string decodes as a space and would
 * corrupt the signature. This test decodes the parameters back and verifies the
 * signature over the server's canonical string, so the whole handshake is proven
 * without a socket.
 */
class WsHandshakeTest {
    private val key: KeyPair = generateKey()

    private val signer =
        object : DeviceSigner {
            override fun publicKeyPem(): String = "unused"

            override fun sign(
                message: String,
                urlSafe: Boolean,
            ): String {
                val s = Signature.getInstance("SHA256withECDSA")
                s.initSign(key.private)
                s.update(message.toByteArray())
                val der = s.sign()

                return if (urlSafe) {
                    Base64.getUrlEncoder().withoutPadding().encodeToString(der)
                } else {
                    Base64.getEncoder().encodeToString(der)
                }
            }
        }

    private fun client(): DeviceWsClient =
        DeviceWsClient(
            baseUrl = "https://api.example.test",
            deviceId = "dev-1",
            signer = signer,
            http = OkHttpClient(),
            listener =
                object : DeviceWsClient.Listener {
                    override fun onTextFrame(raw: String) = Unit

                    override fun onPtyFrame(bytes: ByteArray) = Unit

                    override fun onConnected() = Unit

                    override fun onDisconnected(
                        reason: String,
                        willRetry: Boolean,
                    ) = Unit
                },
            clock = { 1_767_225_600_000 },
        )

    private fun param(
        url: String,
        name: String,
    ): String {
        val query = url.substringAfter('?')
        val value =
            query
                .split('&')
                .first { it.startsWith("$name=") }
                .substringAfter('=')

        return URLDecoder.decode(value, "UTF-8")
    }

    @Test
    fun `the request targets the device stream with the signature parameters`() {
        val request = client().buildRequest()

        val url = request.url.toString()
        assertTrue(url.startsWith("https://api.example.test/v1/device/dev-1/stream?"))
        assertEquals("1767225600000", param(url, "sig_timestamp"))
        assertTrue(param(url, "sig_nonce").isNotEmpty())
        assertTrue(param(url, "sig_signature").isNotEmpty())
    }

    @Test
    fun `the signature is base64url with no padding`() {
        val url = client().buildRequest().url.toString()
        val signature = param(url, "sig_signature")

        // base64url uses - and _, and RawURLEncoding omits '='. A '+' or '/' here
        // means standard base64 leaked in, which a query string would mangle.
        assertTrue(
            "signature must not contain '+' or '/': $signature",
            !signature.contains('+') && !signature.contains('/'),
        )
        assertTrue("signature must be unpadded: $signature", !signature.contains('='))
    }

    @Test
    fun `the signature verifies over the server's connect canonical string`() {
        val url = client().buildRequest().url.toString()
        val ts = param(url, "sig_timestamp")
        val nonce = param(url, "sig_nonce")
        val signature = param(url, "sig_signature")

        val canonical = Signatures.connect("dev-1", ts, nonce)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key.public)
        verifier.update(canonical.toByteArray())

        val sig = Base64.getUrlDecoder().decode(signature)
        assertTrue("the connect signature must verify", verifier.verify(sig))
    }

    private fun generateKey(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
}
