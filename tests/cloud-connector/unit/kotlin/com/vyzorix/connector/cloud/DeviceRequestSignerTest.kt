package com.vyzorix.connector.cloud

import com.vyzorix.connector.identity.DeviceSigner
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTTP request signer must build the same canonical string the server verifies,
 * over the same body bytes, or every device request is refused.
 *
 * The signature is checked here with a real P-256 verification, so the test proves
 * the whole path (canonical string -> sign -> verify) rather than asserting a
 * shape.
 */
class DeviceRequestSignerTest {
    private val key: KeyPair = generateKey()

    /** A [DeviceSigner] over a plain keypair, so no Keystore is needed on the JVM. */
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

    private fun requestSigner(): DeviceRequestSigner =
        DeviceRequestSigner(
            signer = signer,
            deviceId = "dev-1",
            clock = { 1_767_225_600_000 },
            nonceFactory = { "n-1" },
        )

    @Test
    fun `headers carry the timestamp nonce and signature`() {
        val headers =
            requestSigner().headers(
                "POST",
                "/v1/device/dev-1/command",
                """{"a":1}""".toByteArray(),
            )

        assertEquals("1767225600000", headers[DeviceRequestSigner.HEADER_TIMESTAMP])
        assertEquals("n-1", headers[DeviceRequestSigner.HEADER_NONCE])
        assertTrue(headers.containsKey(DeviceRequestSigner.HEADER_SIGNATURE))
    }

    @Test
    fun `the signature verifies against the canonical string the server builds`() {
        val body = """{"command":"SHELL_EXEC"}""".toByteArray()
        val headers = requestSigner().headers("POST", "/v1/device/dev-1/command", body)

        // Reconstruct the server's canonical string and verify.
        val bodyHash =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(body)
        val canonical =
            Signatures.deviceRequest(
                method = "POST",
                path = "/v1/device/dev-1/command",
                deviceId = "dev-1",
                timestampMs = "1767225600000",
                nonce = "n-1",
                bodyHashBase64 = Base64.getEncoder().encodeToString(bodyHash),
            )

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key.public)
        verifier.update(canonical.toByteArray())

        val sig = Base64.getDecoder().decode(headers[DeviceRequestSigner.HEADER_SIGNATURE])
        assertTrue("the request signature must verify", verifier.verify(sig))
    }

    @Test
    fun `a different body produces a different signature`() {
        val a = requestSigner().headers("POST", "/x", "a".toByteArray())
        val b = requestSigner().headers("POST", "/x", "b".toByteArray())

        // Same clock and nonce, so only the body differs: the signatures must differ.
        assertTrue(
            a[DeviceRequestSigner.HEADER_SIGNATURE] != b[DeviceRequestSigner.HEADER_SIGNATURE],
        )
    }

    @Test
    fun `nonces are unique`() {
        val first = DeviceRequestSigner.randomNonce()
        val second = DeviceRequestSigner.randomNonce()

        assertTrue(first != second)
        assertEquals(32, first.length)
    }

    private fun generateKey(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
}
