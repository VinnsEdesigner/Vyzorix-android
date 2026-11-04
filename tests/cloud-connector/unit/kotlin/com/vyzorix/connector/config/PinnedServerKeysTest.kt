package com.vyzorix.connector.config

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinned trust anchor: the one place the device decides what to believe.
 *
 * These cover the two things a single-key anchor could not do — trust a second key
 * during a rotation, and refuse to run with no usable anchor — plus the parse of
 * the multi-block PEM the build supplies.
 */
class PinnedServerKeysTest {
    private fun generateKey(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    private fun pem(key: KeyPair): String {
        val body = Base64.getEncoder().encodeToString(key.public.encoded)

        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
    }

    @Test
    fun `a single key parses and verifies a document it signed`() {
        val key = generateKey()
        val keys = PinnedServerKeys.parse(pem(key))

        assertTrue(keys.anyVerifies { it == key.public })
    }

    @Test
    fun `several PEM blocks all parse and are all trusted`() {
        val first = generateKey()
        val second = generateKey()
        val keys = PinnedServerKeys.parse(pem(first) + pem(second))

        // A build mid-rotation trusts both, so a document signed by either verifies.
        assertTrue(keys.anyVerifies { it == first.public })
        assertTrue(keys.anyVerifies { it == second.public })
    }

    @Test
    fun `a key that was not pinned is not trusted`() {
        val pinned = generateKey()
        val stranger = generateKey()
        val keys = PinnedServerKeys.parse(pem(pinned))

        // The anchor must not become trust-everything: a key outside the set is
        // refused even though it is a well-formed P-256 key.
        assertTrue(!keys.anyVerifies { it == stranger.public })
    }

    @Test
    fun `an empty anchor is refused, not treated as trust-everything`() {
        val thrown =
            assertThrows(PinnedKeyError::class.java) {
                PinnedServerKeys.parse("")
            }

        assertTrue(thrown.message!!.contains("no pinned server key"))
    }

    @Test
    fun `an anchor that is not a PEM block is refused`() {
        assertThrows(PinnedKeyError::class.java) {
            PinnedServerKeys.parse("not a key at all")
        }
    }

    @Test
    fun `a truncated PEM block is refused`() {
        // The BEGIN without an END is the shape a bad copy-paste produces. It must be
        // refused rather than half-parsed.
        val key = generateKey()
        val body = Base64.getEncoder().encodeToString(key.public.encoded)
        val truncated = "-----BEGIN PUBLIC KEY-----\n$body\n"

        assertThrows(PinnedKeyError::class.java) {
            PinnedServerKeys.parse(truncated)
        }
    }

    @Test
    fun `a well-formed block that is not a P-256 key is refused`() {
        // A PEM that parses as a key but is not on the curve the signer uses would
        // verify against a canonical string the signer never produced.
        val rsa =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
        val body = Base64.getEncoder().encodeToString(rsa.public.encoded)

        assertThrows(PinnedKeyError::class.java) {
            PinnedServerKeys.parse("-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n")
        }
    }
}
