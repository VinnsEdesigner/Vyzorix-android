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
    fun `a partial file is resumed with a Range request, not restarted`() {
        // The bug this guards: a network drop mid-download left a partial file, and the
        // next attempt truncated it and started from byte 0. The resume sends a Range
        // from the partial length and appends.
        val whole = "0123456789abcdefghij".toByteArray() // 20 bytes
        val alreadyHave = 8L
        val rest = whole.copyOfRange(alreadyHave.toInt(), whole.size)

        // The server honours the range: 206 with only the remaining bytes.
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(okio.Buffer().write(rest)),
        )

        val destination =
            File.createTempFile("update", ".apk").apply {
                writeBytes(whole.copyOfRange(0, alreadyHave.toInt()))
            }
        val check =
            UpdateCheck(
                updateAvailable = true,
                apkFilename = "app.apk",
                apkSha256 = sha256(whole),
                apkSizeBytes = whole.size.toLong(),
            )

        val file = client().download(check, destination)

        assertEquals("the whole file must be assembled", "0123456789abcdefghij", file.readText())

        val request = server.takeRequest()
        assertEquals("bytes=8-", request.getHeader("Range"))
    }

    @Test
    fun `a server that ignores the Range restarts cleanly`() {
        // A 200 to a Range request means the server will not resume. The partial file
        // must be discarded and rewritten, not appended to (which would corrupt it).
        val whole = "the-whole-payload".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(whole)))

        val destination =
            File.createTempFile("update", ".apk").apply {
                writeBytes("garbage-from-a-failed-attempt".toByteArray())
            }
        val check =
            UpdateCheck(
                updateAvailable = true,
                apkFilename = "app.apk",
                apkSha256 = sha256(whole),
                apkSizeBytes = whole.size.toLong(),
            )

        val file = client().download(check, destination)

        assertEquals("the-whole-payload", file.readText())
    }

    @Test
    fun `a complete staged file is not re-downloaded`() {
        // `matches` is what the caller uses to skip a download; this pins the contract
        // the resume path relies on — a file at the full size is not partial.
        val payload = "complete".toByteArray()
        val destination = File.createTempFile("update", ".apk").apply { writeBytes(payload) }
        val check =
            UpdateCheck(
                updateAvailable = true,
                apkSha256 = sha256(payload),
                apkSizeBytes = payload.size.toLong(),
            )

        assertTrue(client().matches(check, destination))
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

    @Test
    fun `matches is true only for a file whose checksum equals the manifest`() {
        val bytes = "the apk bytes".toByteArray()
        val file = File.createTempFile("update", ".apk").apply { writeBytes(bytes) }

        val good = UpdateCheck(updateAvailable = true, apkSha256 = sha256(bytes))
        assertTrue("a file matching the manifest must be reusable", client().matches(good, file))

        val wrong = UpdateCheck(updateAvailable = true, apkSha256 = sha256("other".toByteArray()))
        assertFalse(
            "a file that does not match must be re-downloaded",
            client().matches(wrong, file),
        )
    }

    @Test
    fun `matches is false when the manifest has no checksum or the file is missing`() {
        val file = File.createTempFile("update", ".apk").apply { writeBytes(byteArrayOf(1)) }

        // No hash means "cannot confirm", which must mean download — never "assume ok".
        assertFalse(client().matches(UpdateCheck(apkSha256 = ""), file))
        assertFalse(client().matches(UpdateCheck(apkSha256 = "abc"), File(file.path + ".missing")))
    }
}
