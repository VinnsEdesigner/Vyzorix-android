package com.vyzorix.connector.config

import com.vyzorix.libv.VParser
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The store must not let a bad document replace a good one, and must report a
 * version mismatch so the device re-fetches. Those are the two behaviours a missed
 * config depends on.
 */
class ConfigStoreTest {
    private val serverKey: KeyPair = generateKey()

    private fun sign(body: String): String {
        val canonical = VParser.canonicalString(body)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(serverKey.private)
        signer.update(canonical)
        val sig = Base64.getEncoder().encodeToString(signer.sign())
        val withNewline = if (body.endsWith("\n")) body else "$body\n"

        return "$withNewline" + "v sig ecdsa-p256 $sig\n"
    }

    private fun body(version: Int): String = "v device dev-1\nvv org org-1\nv version $version\n"

    private class MapStorage : ConfigStorage {
        var value: String? = null

        override fun read(): String? = value

        override fun write(document: String) {
            value = document
        }

        override fun clear() {
            value = null
        }
    }

    private fun store(storage: MapStorage = MapStorage()): Pair<ConfigStore, MapStorage> =
        ConfigStore(storage, ConfigVerifier(serverKey.public)) to storage

    @Test
    fun `applying a good config stores it`() {
        val (s, storage) = store()

        val document = sign(body(1))
        val config = s.apply(document)

        assertEquals(1, config.version)
        // ECDSA is randomised, so the stored text is compared to what was applied,
        // not to a fresh signature of the same body.
        assertEquals(document, storage.value)
        assertEquals(document, config.document)
    }

    @Test
    fun `current returns null when nothing is stored`() {
        val (s, _) = store()

        assertNull(s.current())
    }

    @Test
    fun `a refused config does not replace a good one`() {
        val (s, storage) = store()
        s.apply(sign(body(1)))
        val good = storage.value

        assertThrows(ConfigError.BadSignature::class.java) {
            s.apply(sign(body(2)).replace("v version 2", "v version 9"))
        }

        // The good config survives: a single bad push must not leave the device
        // unconfigured.
        assertEquals(good, storage.value)
        assertEquals(1, s.version())
    }

    @Test
    fun `a tampered stored document is dropped rather than applied`() {
        val storage = MapStorage()
        // Write a document directly, as if the store had been tampered with.
        storage.value = "v device dev-1\nvv org org-1\nv version 1\n"

        val s = ConfigStore(storage, ConfigVerifier(serverKey.public))

        assertNull(s.current())
        assertNull(storage.value)
    }

    @Test
    fun `isStale is true when the server version differs`() {
        val (s, _) = store()
        s.apply(sign(body(1)))

        assertEquals(false, s.isStale(1))
        assertEquals(true, s.isStale(2))
    }

    @Test
    fun `isStale is true when nothing is held`() {
        val (s, _) = store()

        assertEquals(true, s.isStale(1))
    }

    private fun generateKey(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
}
