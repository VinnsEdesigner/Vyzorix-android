package com.vyzorix.connector.secret

import com.vyzorix.connector.config.AppConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The secret store: resolve and decrypt, write a private `.env`, and report a secret
 * that will not open rather than skipping it.
 */
class SecretStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A decryptor that maps payloads back to text, and refuses the rest. */
    private class FakeKey(
        private val values: Map<String, String>,
    ) : SecretDecryptor {
        override fun decrypt(ciphertextBase64: String): ByteArray? =
            values[ciphertextBase64]?.toByteArray(Charsets.UTF_8)
    }

    private class FakeTransport(
        private val secrets: Map<Pair<String, String>, String>,
    ) : SecretTransport {
        override fun ciphertext(
            app: String,
            key: String,
        ): WrappedSecret? = secrets[app to key]?.let { WrappedSecret(it, "RSA-OAEP-SHA1") }
    }

    private fun app(
        name: String,
        secretNames: List<String>,
    ) = AppConfig(
        name = name,
        dir = "/home/apps/$name",
        cmd = "/home/apps/$name/run.sh",
        port = 8080,
        autostart = true,
        expose = "loopback",
        purge = false,
        env = emptyMap(),
        secretNames = secretNames,
        artifacts = emptyList(),
        pwa = false,
        pwaOverrides = emptyMap(),
    )

    @Test
    fun `resolves the decrypted values`() {
        val store =
            SecretStore(
                key = FakeKey(mapOf("c1" to "postgres://db")),
                transport = FakeTransport(mapOf(("vfd" to "DATABASE_URL") to "c1")),
            )

        val resolution = store.resolve(app("vfd", listOf("DATABASE_URL")))

        assertEquals(
            SecretResolution.Resolved(mapOf("DATABASE_URL" to "postgres://db")),
            resolution,
        )
    }

    @Test
    fun `writes the resolved values to a private env file`() {
        val appDir = folder.newFolder("vfd")

        SecretStore(key = FakeKey(emptyMap()), transport = FakeTransport(emptyMap()))
            .writeEnv(appDir, mapOf("DATABASE_URL" to "postgres://db"))

        val env = File(appDir, ".env")
        assertEquals("DATABASE_URL=postgres://db\n", env.readText())
        assertFalse(env.canExecute())
    }

    @Test
    fun `writing an empty set creates no file`() {
        val appDir = folder.newFolder("vfd")

        SecretStore(key = FakeKey(emptyMap()), transport = FakeTransport(emptyMap()))
            .writeEnv(appDir, emptyMap())

        assertFalse(File(appDir, ".env").exists())
    }

    @Test
    fun `a ciphertext that will not open is reported, not skipped`() {
        val store =
            SecretStore(
                key = FakeKey(emptyMap()),
                transport = FakeTransport(mapOf(("vfd" to "DATABASE_URL") to "c1")),
            )

        val resolution = store.resolve(app("vfd", listOf("DATABASE_URL")))

        assertEquals(SecretResolution.Undecryptable("DATABASE_URL"), resolution)
    }

    @Test
    fun `an unset secret is simply absent`() {
        val store =
            SecretStore(
                key = FakeKey(mapOf("c1" to "value")),
                transport = FakeTransport(mapOf(("vfd" to "DATABASE_URL") to "c1")),
            )

        // S3_SECRET_KEY is named but not set on the server.
        val resolution = store.resolve(app("vfd", listOf("DATABASE_URL", "S3_SECRET_KEY")))

        assertEquals(SecretResolution.Resolved(mapOf("DATABASE_URL" to "value")), resolution)
    }
}
