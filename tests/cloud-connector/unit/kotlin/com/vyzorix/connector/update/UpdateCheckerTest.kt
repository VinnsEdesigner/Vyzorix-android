package com.vyzorix.connector.update

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The pass-level behaviour around the install prompt.
 *
 * Two properties the user relies on:
 *
 *  1. a build that was already offered is not re-downloaded on the next connect
 *     (the standing notification keeps the prompt alive, so no re-offer is needed); and
 *  2. the prompt is cleared only when a fresh check confirms the device is already on
 *     the latest build — i.e. the install actually landed.
 *
 * The real [UpdateClient] talks to a [MockWebServer], so the check/download/verify path
 * is exercised rather than faked; only the final install is a recording stub, because a
 * JVM test cannot drive the system installer.
 */
class UpdateCheckerTest {
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

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** A manifest naming [payload] as the update, at version [code]. */
    private fun updateAvailable(
        code: Int,
        payload: ByteArray,
    ): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setBody(
                """{"update_available":true,"version":"9.9.9","version_code":$code,""" +
                    """"apk_filename":"app.apk","apk_sha256":"${sha256(payload)}",""" +
                    """"apk_size_bytes":${payload.size}}""",
            )

    /** The "nothing newer" answer, at version [code], for an up-to-date device. */
    private fun upToDate(code: Int): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setBody("""{"update_available":false,"version":"9.9.9","version_code":$code}""")

    private class RecordingInstaller : ApkInstaller {
        var installs = 0

        override fun install(
            apk: File,
            version: String,
        ): Boolean {
            installs++

            return true
        }
    }

    private fun checker(
        installer: RecordingInstaller,
        staging: File,
        currentVersionCode: Int = 1,
        onUpToDate: () -> Unit = {},
    ): UpdateChecker =
        UpdateChecker(
            client =
                UpdateClient(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    http = OkHttpClient(),
                ),
            installer = installer,
            currentVersionCode = currentVersionCode,
            stagingDir = staging,
            onUpToDate = onUpToDate,
        )

    @Test
    fun `a build already offered is not re-downloaded on the next pass`() {
        val payload = "apk-bytes-for-the-update".toByteArray()
        server.enqueue(updateAvailable(code = 2, payload))
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))
        // The next connect sees the same build. The staged APK must not be re-fetched.
        server.enqueue(updateAvailable(code = 2, payload))

        val installer = RecordingInstaller()
        val staging =
            File.createTempFile("update-checker", "").apply {
                delete()
                mkdirs()
            }
        val checker = checker(installer, staging)

        checker.runOnce()
        assertEquals("the first pass must offer the install", 1, installer.installs)

        checker.runOnce()
        assertEquals(
            "an already-offered build must not be offered again (no re-download)",
            1,
            installer.installs,
        )

        assertEquals(
            "exactly two checks and one apk fetch must have hit the server",
            3,
            server.requestCount,
        )
    }

    @Test
    fun `a confirmed up-to-date check clears the install prompt`() {
        // The device is on the latest build, so the server has nothing newer. This is
        // the moment the offered install is known to have landed.
        server.enqueue(upToDate(code = 9))

        var cleared = false
        val installer = RecordingInstaller()
        val staging =
            File.createTempFile("update-checker", "").apply {
                delete()
                mkdirs()
            }

        checker(installer, staging, currentVersionCode = 9) { cleared = true }.runOnce()

        assertTrue("an up-to-date device must clear the Accept install prompt", cleared)
    }

    @Test
    fun `an available update does not clear the install prompt`() {
        val payload = "apk-bytes-for-the-update".toByteArray()
        server.enqueue(updateAvailable(code = 2, payload))
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))

        var cleared = false
        val installer = RecordingInstaller()
        val staging =
            File.createTempFile("update-checker", "").apply {
                delete()
                mkdirs()
            }

        checker(installer, staging) { cleared = true }.runOnce()

        assertFalse("a pending install must keep the prompt", cleared)
    }
}
