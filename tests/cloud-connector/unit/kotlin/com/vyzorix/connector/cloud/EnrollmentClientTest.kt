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
 * The enroll request shape and the error paths, against a real HTTP server rather
 * than a mock: the request must carry the exact JSON the Go server binds, and the
 * response parser must accept what it sends.
 */
class EnrollmentClientTest {
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

    @Test
    fun `enroll posts the device identity and parses the code`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """{"deviceId":"dev-1","pairingCode":"K7QM3X2P",""" +
                        """"pairingDisplay":"K7QM-3X2P",""" +
                        """"expiresAt":1767225600000,"expiresInSeconds":600}""",
                ),
        )

        val response =
            client().enroll(
                EnrollRequest(
                    deviceUuid = "dev-1",
                    publicKey = signer.publicKeyPem(),
                    model = "C22",
                    manufacturer = "Nokia",
                    osVersion = "13",
                    appVersion = "0.1.0",
                    deviceClass = "phone",
                    tierHint = 1,
                ),
            )

        assertEquals("K7QM3X2P", response.pairingCode)
        assertEquals("K7QM-3X2P", response.pairingDisplay)
        assertEquals(600, response.expiresInSeconds)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/device/enroll", recorded.path)
        val body = recorded.body.readUtf8()
        // The server binds these exact field names.
        assertTrue(body.contains(""""deviceUuid":"dev-1""""))
        assertTrue(body.contains(""""publicKey":""""))
        assertTrue(body.contains(""""tierHint":1"""))
    }

    @Test
    fun `a 409 is reported as already claimed and is not retryable`() {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody("""{"error":"already claimed"}"""),
        )

        val error =
            try {
                client().enroll(EnrollRequest("dev-1", "pem"))
                null
            } catch (e: CloudError) {
                e
            }

        assertEquals(409, error?.status)
        assertEquals(true, error?.isAlreadyClaimed)
        assertEquals(false, error?.isRetryable)
    }

    @Test
    fun `a 429 is retryable`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"slow down"}"""))

        val error =
            try {
                client().enroll(EnrollRequest("dev-1", "pem"))
                null
            } catch (e: CloudError) {
                e
            }

        assertEquals(true, error?.isRetryable)
    }

    @Test
    fun `fetchConfig signs the request and parses the document`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"deviceId":"dev-1","version":1,"issuedAt":1,"document":"v device dev-1\n"}""",
                ),
        )

        val requestSigner =
            DeviceRequestSigner(
                signer = signer,
                deviceId = "dev-1",
                clock = { 1_767_225_600_000 },
                nonceFactory = { "n-1" },
            )

        val response = client().fetchConfig(requestSigner)

        assertEquals(1, response.version)
        assertEquals("v device dev-1\n", response.document)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/device/dev-1/config", recorded.path)
        // The device signature headers the server's requireHMAC reads.
        assertTrue(recorded.getHeader("X-Vyzorix-Timestamp") != null)
        assertTrue(recorded.getHeader("X-Vyzorix-Nonce") != null)
        assertTrue(recorded.getHeader("X-Vyzorix-Signature") != null)
    }
}
