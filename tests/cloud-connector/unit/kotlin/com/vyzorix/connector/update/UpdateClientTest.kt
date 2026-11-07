package com.vyzorix.connector.update

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The OTA client against a real HTTP server.
 *
 * The checksum is the point of this test: the download path is where a bad response
 * would become installed code, so a mismatched hash must refuse the file rather than
 * return it. The rest is the request shape and the null-on-failure contract.
 */
class UpdateClientTest {
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

    private fun client(): UpdateClient =
        UpdateClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            http = OkHttpClient(),
        )

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `check parses the flattened manifest and sends the version code`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"update_available":true,"version":"1.1.0","version_code":2,""" +
                        """"apk_filename":"app-v1.1.0.apk","apk_sha256":"abc",""" +
                        """"release_notes":"notes","apk_size_bytes":123}""",
                ),
        )

        val check = client().check(currentVersionCode = 1)

        assertEquals(true, check?.updateAvailable)
        assertEquals(2, check?.versionCode)
        assertEquals("app-v1.1.0.apk", check?.apkFilename)

        val request = server.takeRequest()
        assertEquals("/v1/check-update?version_code=1", request.path)
    }

    @Test
    fun `check returns null when the server is unreachable`() {
        server.shutdown()

        assertNull(client().check(currentVersionCode = 1))
    }

    @Test
    fun `download verifies the checksum and returns the file`() {
        val payload = "apk-bytes".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))

        val destination = File.createTempFile("update", ".apk").apply { delete() }
        val check =
            UpdateCheck(
                updateAvailable = true,
                apkFilename = "app.apk",
                apkSha256 = sha256(payload),
            )

        val file = client().download(check, destination)

        assertTrue(file.exists())
        assertEquals("apk-bytes", file.readText())

        val request = server.takeRequest()
        assertEquals("/v1/apk/app.apk", request.path)
    }

    @Test
    fun `download refuses a file whose checksum does not match`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("tampered"))

        val destination = File.createTempFile("update", ".apk").apply { delete() }
        val check =
            UpdateCheck(
                updateAvailable = true,
                apkFilename = "app.apk",
                apkSha256 = sha256("expected".toByteArray()),
            )

        val threw =
            try {
                client().download(check, destination)

                false
            } catch (e: Exception) {
                true
            }

        assertTrue("a mismatched checksum must be refused", threw)
        assertFalse("the bad file must be deleted", destination.exists())
    }

    @Test
    fun `download refuses a manifest with no checksum`() {
        val destination = File.createTempFile("update", ".apk").apply { delete() }
        val check = UpdateCheck(updateAvailable = true, apkFilename = "app.apk", apkSha256 = "")

        val threw =
            try {
                client().download(check, destination)

                false
            } catch (e: Exception) {
                true
            }

        assertTrue("an unverified download must be refused", threw)
    }
}
