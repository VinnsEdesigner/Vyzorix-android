package com.vyzorix.connector.cloud

import com.vyzorix.connector.identity.DeviceSigner
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The enroll poll-interval contract, against a real HTTP server.
 *
 * The server tells the device how often to ask for its config while it waits to be
 * claimed (`pollIntervalSeconds`). The device must parse it and honour it, and must
 * still work against an older server that does not send the field at all — hence
 * the default.
 */
class EnrollPollIntervalTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): EnrollmentClient =
        EnrollmentClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            http = OkHttpClient(),
            deviceIdProvider = { "dev-1" },
        )

    private val signer =
        object : DeviceSigner {
            override fun publicKeyPem(): String =
                "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"

            override fun sign(
                message: String,
                urlSafe: Boolean,
            ): String = "sig"
        }

    private fun enrollWith(body: String): EnrollResponse {
        server.enqueue(MockResponse().setResponseCode(201).setBody(body))

        return client().enroll(
            EnrollRequest(deviceUuid = "dev-1", publicKey = signer.publicKeyPem()),
        )
    }

    @Test
    fun `the server's poll interval is parsed`() {
        val response =
            enrollWith(
                """{"deviceId":"dev-1","pairingCode":"K7QM3X2P","pairingDisplay":"K7QM-3X2P",""" +
                    """"expiresAt":1767225600000,"expiresInSeconds":600,"pollIntervalSeconds":10}""",
            )

        assertEquals(10L, response.pollIntervalSeconds)
    }

    @Test
    fun `an older server that omits the interval still yields a sane cadence`() {
        val response =
            enrollWith(
                """{"deviceId":"dev-1","pairingCode":"K7QM3X2P","pairingDisplay":"K7QM-3X2P",""" +
                    """"expiresAt":1767225600000,"expiresInSeconds":600}""",
            )

        assertEquals(EnrollResponse.DEFAULT_POLL_INTERVAL_SECONDS, response.pollIntervalSeconds)
    }

    @Test
    fun `the device polls on the server's cadence and stops once configured`() {
        var fetches = 0
        val poller =
            ConfigPoller(
                intervalSeconds = 1,
                fetch = {
                    fetches++

                    // Configured on the second poll, like a claim that landed a moment
                    // after the device started waiting.
                    fetches >= 2
                },
            )

        poller.start()
        Thread.sleep(4_000)
        val afterConfigured = poller.pollCount.get()

        // Stopped: no further polls after the config arrived.
        Thread.sleep(3_000)

        assertEquals(2, afterConfigured)
        assertEquals(2, poller.pollCount.get())
    }

    @Test
    fun `the poller polls immediately rather than waiting a full interval`() {
        val poller = ConfigPoller(intervalSeconds = 60, fetch = { false })

        poller.start()
        // A device claimed while the socket is down should not wait a whole interval
        // for its first ask.
        Thread.sleep(1_000)
        poller.stop()

        assertTrue("the first poll must run at once", poller.pollCount.get() >= 1)
    }

    @Test
    fun `a non-positive poll interval is refused`() {
        val thrown =
            runCatching { ConfigPoller(intervalSeconds = 0, fetch = { false }) }
                .exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `enroll sends nothing about the cadence, which is the server's to own`() {
        enrollWith(
            """{"deviceId":"dev-1","pairingCode":"K7QM3X2P","pairingDisplay":"K7QM-3X2P",""" +
                """"expiresAt":1767225600000,"expiresInSeconds":600,"pollIntervalSeconds":10}""",
        )

        val recorded = server.takeRequest()
        assertEquals(1, server.requestCount)
        // The request carries only identity and facts; the server decides the cadence
        // so it can be retuned without shipping a new APK.
        assertTrue(!recorded.body.readUtf8().contains("pollIntervalSeconds"))
    }
}
