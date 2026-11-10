package com.vyzorix.connector.secret

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The secret envelope, end to end, on the device: a real Keystore key, a ciphertext
 * the **server's own `WrapSecret`** produced, and the device decrypting it.
 *
 * This is the roundtrip the unit tests cannot cover — they fake one side. It proves
 * the interop that the contract's spike warned about: the Android Keystore accepts
 * only `MGF1-SHA1`, so a "hardened to SHA-256" envelope would fail here and nowhere
 * else. It also proves the negative: a ciphertext wrapped to a different key does
 * not open.
 *
 * ## Running it
 *
 * The host side must wrap with the server's own Go `crypto.WrapSecret`, so a small
 * helper serves the ciphertext (it takes the device's PEM and returns the wrapped
 * value). Point the test at it with the instrumentation argument and forward the
 * port:
 *
 * ```
 * # a host helper that calls the server's crypto.WrapSecret for ?pem=<device PEM>
 * adb reverse tcp:3000 tcp:3000
 * ./gradlew :cloud-connector:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.serverUrl=http://127.0.0.1:3000 \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.vyzorix.connector.secret.SecretEnvelopeRoundtripTest
 * ```
 *
 * The test is skipped when the helper is not reachable, like the enrollment E2E.
 */
@RunWith(AndroidJUnit4::class)
class SecretEnvelopeRoundtripTest {
    private val serverUrl: String =
        androidx.test.platform.app.InstrumentationRegistry
            .getArguments()
            .getString("serverUrl")
            ?: "http://127.0.0.1:3000"

    /** The plaintext the host helper wraps, matching `/tmp/secret_server.py`. */
    private val expected = "postgres://vyzorix:s3cr3t@db.internal:5432/app"

    private fun key(alias: String): SecretKey =
        SecretKey(KeyStore.getInstance(SecretKey.ANDROID_KEYSTORE), alias)

    /** Asks the host to wrap [expected] to [pem] using the server's Go code. */
    private fun wrap(pem: String): String {
        val url = URL("$serverUrl/wrap?pem=" + URLEncoder.encode(pem, "UTF-8"))
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 20000

        return connection.inputStream
            .bufferedReader()
            .readText()
            .trim()
    }

    /** The reason the host helper is not reachable, or null when it is. */
    private fun unreachableReason(): String? =
        try {
            wrap("probe")

            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }

    @Test
    fun aSecretWrappedByTheServerDecryptsOnTheDevice() {
        unreachableReason()?.let { assumeTrue("host helper unreachable: $it", false) }

        val secretKey = key("vyzorix.secret.roundtrip")
        val ciphertext = wrap(secretKey.publicKeyPem())

        assertNotNull("host returned no ciphertext", ciphertext)
        val plaintext = secretKey.decrypt(ciphertext)

        assertNotNull("the device could not open the server's ciphertext", plaintext)
        assertEquals(expected, String(plaintext!!, Charsets.UTF_8))
    }

    @Test
    fun aCiphertextWrappedToAnotherKeyDoesNotOpen() {
        unreachableReason()?.let { assumeTrue("host helper unreachable: $it", false) }

        // Wrapped to key A, decrypted with key B: must fail, not return garbage.
        val keyA = key("vyzorix.secret.roundtrip.a")
        val keyB = key("vyzorix.secret.roundtrip.b")
        val ciphertext = wrap(keyA.publicKeyPem())

        assertNull("a foreign ciphertext must not open", keyB.decrypt(ciphertext))
    }
}
