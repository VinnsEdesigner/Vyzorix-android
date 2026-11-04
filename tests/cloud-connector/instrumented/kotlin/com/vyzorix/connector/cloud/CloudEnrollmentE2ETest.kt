package com.vyzorix.connector.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device's own code against a **real running server**, on the emulator.
 *
 * This is the piece the unit tests cannot cover and the piece that was missing:
 * every other test in this repo fakes one side of the wire, so a contract drift
 * between the device's Kotlin and the server's Go would pass them all and only
 * surface on a device.
 *
 * What it exercises, in the order the real device does it:
 *
 *  1. Enroll over HTTP with a real Keystore keypair, and read the pairing code.
 *  2. Open the device WebSocket with the signed `connect|…` handshake, using the
 *     same [DeviceWsClient] the app uses.
 *  3. Send a telemetry frame and confirm the server accepts the socket (a bad
 *     handshake or a malformed frame drops the connection).
 *
 * ## Running it
 *
 * It needs a server, so it is skipped unless one is reachable. Point it at one with
 * the instrumentation arguments, and forward the port from the emulator:
 *
 * ```
 * adb reverse tcp:3000 tcp:3000
 * ./gradlew :cloud-connector:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.serverUrl=http://127.0.0.1:3000
 * ```
 *
 * The default `127.0.0.1:3000` works with `adb reverse`; `10.0.2.2:3000` reaches the
 * host directly without it.
 */
@RunWith(AndroidJUnit4::class)
class CloudEnrollmentE2ETest {
    private val serverUrl: String =
        androidx.test.platform.app.InstrumentationRegistry
            .getArguments()
            .getString("serverUrl")
            ?: "http://127.0.0.1:3000"

    /** A device keypair, generated the way the device generates its own. */
    private fun generateKey(): java.security.KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    private fun pem(key: java.security.KeyPair): String {
        val body = Base64.getEncoder().encodeToString(key.public.encoded)

        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
    }

    /**
     * The reason no server answered, or null when one did.
     *
     * Returns the *reason* rather than a boolean so a skip says why: a silent
     * `assumeTrue(false)` reads as "no server configured" when the truth may be a
     * TLS refusal or a wrong host, which is the difference between a green run and a
     * test that never ran.
     */
    private fun serverReachabilityError(): String? =
        try {
            OkHttpClient()
                .newCall(Request.Builder().url("$serverUrl/health").build())
                .execute()
                .use { if (it.isSuccessful) null else "HTTP ${it.code}" }
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        }

    @Test
    fun theDeviceEnrollsAndHoldsASignedWebSocket() {
        val unreachable = serverReachabilityError()
        assumeTrue(
            "no server at $serverUrl ($unreachable); skipping the live test",
            unreachable == null,
        )

        val key = generateKey()
        val deviceUuid =
            "e2e-device-" +
                java.util.UUID
                    .randomUUID()
                    .toString()
                    .take(8)
        val http = OkHttpClient()

        // --- 1. Enroll, through the client the app uses. ---
        val enrollment =
            EnrollmentClient(
                baseUrl = serverUrl,
                http = http,
                deviceIdProvider = { deviceUuid },
            )

        val response =
            enrollment.enroll(
                EnrollRequest(
                    deviceUuid = deviceUuid,
                    publicKey = pem(key),
                    model = "C22",
                    manufacturer = "Nokia",
                    osVersion = "13",
                    appVersion = "0.1.0",
                    deviceClass = "phone",
                    tierHint = 1,
                ),
            )

        assertTrue("enroll must return a device id", response.deviceId.isNotEmpty())
        assertTrue("enroll must return a pairing code", response.pairingCode.isNotEmpty())
        assertTrue(
            "the server must tell the device the config poll interval",
            response.pollIntervalSeconds > 0,
        )

        // --- 2. Hold the signed WebSocket, using the device's own client. ---
        val connected = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()

        val signer =
            object : com.vyzorix.connector.identity.DeviceSigner {
                override fun publicKeyPem(): String = pem(key)

                override fun sign(
                    message: String,
                    urlSafe: Boolean,
                ): String {
                    val s = Signature.getInstance("SHA256withECDSA")
                    s.initSign(key.private)
                    s.update(message.toByteArray())

                    return if (urlSafe) {
                        Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign())
                    } else {
                        Base64.getEncoder().encodeToString(s.sign())
                    }
                }
            }

        val client =
            DeviceWsClient(
                baseUrl = serverUrl,
                deviceId = response.deviceId,
                signer = signer,
                http = http,
                listener =
                    object : DeviceWsClient.Listener {
                        override fun onTextFrame(raw: String) {
                            received += raw
                        }

                        override fun onPtyFrame(bytes: ByteArray) = Unit

                        override fun onConnected() {
                            connected.countDown()
                        }

                        override fun onDisconnected(
                            reason: String,
                            willRetry: Boolean,
                        ) {
                            failures += reason
                        }
                    },
            )

        client.connect()
        val opened = connected.await(20, java.util.concurrent.TimeUnit.SECONDS)

        assertTrue(
            "the signed connect handshake must be accepted; the server dropped it: $failures",
            opened,
        )

        // --- 3. A telemetry frame must not break the socket. ---
        // The server routes on the top-level `type`; a frame it cannot route is
        // logged and dropped, but a malformed *socket* frame would close it. Sending
        // one and then confirming the socket still carries traffic is the check.
        val sent =
            client.sendText(
                CloudJson.outbound.encodeToString(
                    TelemetryFrame(
                        timestamp = System.currentTimeMillis(),
                        deviceId = response.deviceId,
                        uptime = 4_200,
                        thermalTemp = 31.5,
                    ),
                ),
            )
        assertTrue("the socket must accept a telemetry frame", sent)

        Thread.sleep(1_000)
        assertTrue(
            "the socket must still be up after a telemetry frame: $failures",
            failures.isEmpty(),
        )

        client.close()
    }
}
