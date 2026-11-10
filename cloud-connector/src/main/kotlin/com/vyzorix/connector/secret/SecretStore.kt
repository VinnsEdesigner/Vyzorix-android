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

/** The result of resolving an app's secrets: the values, or the key that will not open. */
sealed interface SecretResolution {
    data class Resolved(
        val values: Map<String, String>,
    ) : SecretResolution

    data class Undecryptable(
        val key: String,
    ) : SecretResolution
}

/**
 * Fetches and decrypts an app's secrets, and writes them where the supervisor reads
 * them.
 *
 * Resolution and writing are separate so the applier can validate every secret
 * **before** it touches the live tree: a secret that will not open aborts with the
 * device unchanged, rather than after an artifact swap.
 *
 * A decrypted value is written to `files/apps/<app>/.env` at mode `0600` and is never
 * put in `apps.json`: the registry is inside `/home/apps`, which every app in the
 * container can read, so a secret there would hand one app another's credential.
 *
 * A secret that will not decrypt is a refusal, not a silent skip: starting
 * the app with the variable absent produces an app that looks up but talks to the
 * wrong thing.
 */
class SecretStore(
    private val key: SecretDecryptor,
    private val transport: SecretTransport,
) {
    /**
     * Fetches and decrypts [app]'s secrets, or names the first that will not open.
     *
     * A named secret the server has not set is simply absent; only a value that is
     * present and will not decrypt is a refusal.
     */
    fun resolve(app: AppConfig): SecretResolution {
        val values = linkedMapOf<String, String>()
        for (name in app.secretNames) {
            val wrapped = transport.ciphertext(app.name, name) ?: continue
            val plaintext = key.decrypt(wrapped.ciphertext)
            if (plaintext == null) {
                return SecretResolution.Undecryptable(name)
            }
            values[name] = String(plaintext, Charsets.UTF_8)
        }

        return SecretResolution.Resolved(values)
    }

    /** Writes `KEY=VALUE` lines at mode 0600, creating the app directory if needed. */
    fun writeEnv(
        appDir: File,
        values: Map<String, String>,
    ) {
        if (values.isEmpty()) return

        val file = File(appDir, ENV_FILE)
        file.parentFile?.mkdirs()

        file.writeText(values.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=$v" })
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    private companion object {
        const val ENV_FILE = ".env"
    }
}
