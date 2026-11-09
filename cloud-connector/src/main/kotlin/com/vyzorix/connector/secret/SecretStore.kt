package com.vyzorix.connector.secret

import com.vyzorix.connector.config.AppConfig
import java.io.File
import java.io.IOException

/**
 * The ciphertext for one secret, as the server serves it. The value never appears
 * here: only the wrapped bytes and the algorithm the device must agree on.
 */
fun interface SecretTransport {
    /**
     * Fetches the wrapped value for `app.key`, or null when the server has none.
     *
     * @throws IOException on a network failure. A null return means "not set".
     */
    fun ciphertext(
        app: String,
        key: String,
    ): WrappedSecret?
}

/** A ciphertext and the envelope algorithm the server used to produce it. */
data class WrappedSecret(
    val ciphertext: String,
    val envelope: String,
)

/**
 * Decrypts a base64 RSA-OAEP-SHA1 ciphertext, or returns null when it will not open.
 *
 * A seam so [SecretStore] is testable on the JVM: the real implementation is
 * [SecretKey], whose key lives in the Android Keystore and cannot be constructed in a
 * unit test.
 */
fun interface SecretDecryptor {
    fun decrypt(ciphertextBase64: String): ByteArray?
}

/**
 * Fetches, decrypts and writes an app's secrets
 * (VYZORIX_DEPLOY_CONTRACT.md §9b.2, §9b.4, FR-26).
 *
 * A decrypted value is written to `files/apps/<app>/.env` at mode `0600` and is
 * never put in `apps.json`: the registry is inside `/home/apps`, which every app in
 * the container can read, so a secret there would hand one app another's credential.
 * The supervisor reads the `.env` at start.
 *
 * A secret that will not decrypt is a refusal, not a silent skip (§9b.5): starting
 * the app with the variable absent produces an app that looks up but talks to the
 * wrong thing.
 */
class SecretStore(
    private val key: SecretDecryptor,
    private val transport: SecretTransport,
) {
    /**
     * Writes [app]'s secrets to [appDir]/.env at mode 0600.
     *
     * @return the name of a secret that would not open, or null on success. An app
     *   with no secrets writes no file.
     */
    fun write(
        app: AppConfig,
        appDir: File,
    ): String? {
        if (app.secretNames.isEmpty()) return null

        val values = linkedMapOf<String, String>()
        for (name in app.secretNames) {
            val wrapped = transport.ciphertext(app.name, name) ?: continue
            val plaintext = key.decrypt(wrapped.ciphertext) ?: return name
            values[name] = String(plaintext, Charsets.UTF_8)
        }

        writeEnvFile(File(appDir, ENV_FILE), values)

        return null
    }

    /** Writes `KEY=VALUE` lines at mode 0600, creating the app directory if needed. */
    private fun writeEnvFile(
        file: File,
        values: Map<String, String>,
    ) {
        file.parentFile?.mkdirs()

        val text = values.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=$v" }
        file.writeText(text)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    private companion object {
        const val ENV_FILE = ".env"
    }
}
