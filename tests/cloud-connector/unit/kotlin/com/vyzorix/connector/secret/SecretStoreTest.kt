package com.vyzorix.connector.secret

import com.vyzorix.connector.config.AppConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The secret store: decrypt, write a `0600` `.env`, and refuse rather than skip a
 * secret that will not open (VYZORIX_DEPLOY_CONTRACT.md §9b.4–§9b.5, FR-26).
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
    fun `writes the decrypted values to a private env file`() {
        val appDir = folder.newFolder("vfd")
        val store =
            SecretStore(
                key = FakeKey(mapOf("c1" to "postgres://db")),
                transport = FakeTransport(mapOf(("vfd" to "DATABASE_URL") to "c1")),
            )

        val failure = store.write(app("vfd", listOf("DATABASE_URL")), appDir)

        assertNull(failure)
        val env = File(appDir, ".env")
        assertEquals("DATABASE_URL=postgres://db\n", env.readText())
        assertFalse(env.canExecute())
    }

    @Test
    fun `an app with no secrets writes no file`() {
        val appDir = folder.newFolder("vfd")
        val store = SecretStore(key = FakeKey(emptyMap()), transport = FakeTransport(emptyMap()))

        assertNull(store.write(app("vfd", emptyList()), appDir))
        assertFalse(File(appDir, ".env").exists())
    }

    @Test
    fun `a ciphertext that will not open is a refusal, not a skip`() {
        val appDir = folder.newFolder("vfd")
        val store =
            SecretStore(
                key = FakeKey(emptyMap()),
                transport = FakeTransport(mapOf(("vfd" to "DATABASE_URL") to "c1")),
            )

        val failure = store.write(app("vfd", listOf("DATABASE_URL")), appDir)

        assertEquals("DATABASE_URL", failure)
        assertFalse(File(appDir, ".env").exists())
    }

    @Test
    fun `an unset secret is simply absent`() {
        val appDir = folder.newFolder("vfd")
        val store =
            SecretStore(
                key = FakeKey(mapOf("c1" to "value")),
                transport = FakeTransport(mapOf(("vfd" to "DATABASE_URL") to "c1")),
            )

        // S3_SECRET_KEY is named but not set on the server.
        val failure = store.write(app("vfd", listOf("DATABASE_URL", "S3_SECRET_KEY")), appDir)

        assertNull(failure)
        assertEquals("DATABASE_URL=value\n", File(appDir, ".env").readText())
    }
}
