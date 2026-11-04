package com.vyzorix.connector.config

import com.vyzorix.libv.VParser
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config verifier is the device's trust boundary: an unverified config is an
 * attacker's config. These tests sign a document the way the **server** signs it —
 * over the format's canonical string, with a P-256 key — so a drift between the
 * server's signing and libv's verification shows up here.
 *
 * The device holds only the public key, which is what [ConfigVerifier] takes.
 */
class ConfigVerifierTest {
    private val serverKey: KeyPair = generateKey()

    private fun verifier(): ConfigVerifier = ConfigVerifier(serverKey.public)

    /** Signs a document the way libv.Signer.Sign does: append a `v sig` line. */
    private fun sign(
        body: String,
        key: KeyPair = serverKey,
    ): String {
        val canonical = VParser.canonicalString(body)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(key.private)
        signer.update(canonical)
        val signature = Base64.getEncoder().encodeToString(signer.sign())

        val withNewline = if (body.endsWith("\n")) body else "$body\n"

        return "$withNewline" + "v sig ecdsa-p256 $signature\n"
    }

    private fun validBody(): String =
        """
        ' vyzorix.v
        v device dev-1
        vv org org-1
        vv name field-ops-07
        vv tier 2
        vv cloud
        vvv stream /v1/device/dev-1/stream
        vvv hb 60s
        vv features
        vvv terminal true
        vvv usb false
        vvv apps false
        v version 1
        """.trimIndent() + "\n"

    @Test
    fun `a correctly signed config verifies and reads back`() {
        val config = verifier().verify(sign(validBody()))

        assertEquals("dev-1", config.deviceId)
        assertEquals("org-1", config.organizationId)
        assertEquals("field-ops-07", config.name)
        assertEquals(2, config.tierCap)
        assertEquals("/v1/device/dev-1/stream", config.streamPath)
        assertEquals(60, config.heartbeatSeconds)
        assertTrue(config.terminalEnabled)
        assertEquals(false, config.usbEnabled)
        assertEquals(false, config.appsEnabled)
        assertEquals(1, config.version)
    }

    @Test
    fun `a tampered field fails verification`() {
        val signed = sign(validBody())
        // Change the tier after signing: the signature covers it.
        val tampered = signed.replace("vv tier 2", "vv tier 9")

        assertThrows(ConfigError.BadSignature::class.java) {
            verifier().verify(tampered)
        }
    }

    @Test
    fun `an unsigned config is refused`() {
        assertThrows(ConfigError.Unsigned::class.java) {
            verifier().verify(validBody())
        }
    }

    @Test
    fun `a config signed by the wrong key is refused`() {
        val attacker = generateKey()
        val signed = sign(validBody(), attacker)

        assertThrows(ConfigError.BadSignature::class.java) {
            verifier().verify(signed)
        }
    }

    @Test
    fun `a config carrying a secret is refused`() {
        // The invariant the whole design rests on: a config is transmitted, and a
        // secret in it would reintroduce the exact hole the keypair model removed.
        val body =
            """
            v device dev-1
            vv org org-1
            vv token abc123
            v version 1
            """.trimIndent() + "\n"

        assertThrows(ConfigError.CarriesASecret::class.java) {
            verifier().verify(sign(body))
        }
    }

    @Test
    fun `a config carrying a password is refused`() {
        val body =
            """
            v device dev-1
            vv org org-1
            vv password hunter2
            v version 1
            """.trimIndent() + "\n"

        assertThrows(ConfigError.CarriesASecret::class.java) {
            verifier().verify(sign(body))
        }
    }

    @Test
    fun `the signature line naming an algorithm is not treated as a secret`() {
        // `v sig ecdsa-p256` names an algorithm; the secret scan must skip it or
        // every signed config would be refused.
        val config = verifier().verify(sign(validBody()))

        assertEquals("dev-1", config.deviceId)
    }

    @Test
    fun `a malformed document is refused as malformed`() {
        // A depth jump is a parse error.
        val body = "v device dev-1\nvvv org org-1\n"

        assertThrows(ConfigError.Malformed::class.java) {
            verifier().verify(body)
        }
    }

    @Test
    fun `a missing org is malformed`() {
        val body = "v device dev-1\nv version 1\n"

        assertThrows(ConfigError.Malformed::class.java) {
            verifier().verify(sign(body))
        }
    }

    @Test
    fun `missing features default to off`() {
        // Fail-closed: a config that does not mention a feature does not enable it.
        val body = "v device dev-1\nvv org org-1\nv version 1\n"
        val config = verifier().verify(sign(body))

        assertEquals(false, config.terminalEnabled)
        assertEquals(false, config.usbEnabled)
        assertEquals(false, config.appsEnabled)
        assertEquals(ConfigVerifier.DEFAULT_HEARTBEAT_SECONDS, config.heartbeatSeconds)
    }

    private fun generateKey(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
}
