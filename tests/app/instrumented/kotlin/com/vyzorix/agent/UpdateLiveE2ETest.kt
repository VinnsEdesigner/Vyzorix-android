package com.vyzorix.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyzorix.connector.update.ApkInstaller
import com.vyzorix.connector.update.UpdateChecker
import com.vyzorix.connector.update.UpdateClient
import java.io.File
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The OTA mechanism, end to end against a **live server**.
 *
 * This is the real flow: `UpdateClient` (the production HTTP client) talks to the
 * server the emulator reaches at `10.0.2.2:3000`, `UpdateChecker` runs the whole
 * pass, and the APK is downloaded and checksum-verified exactly as on a device.
 *
 * ## The host server
 *
 * `10.0.2.2` is the emulator's alias for the host loopback — the server runs on the
 * host at :3000. `adb reverse` did not work here (recorded in memory), which is why
 * the alias is used. The test **skips with a reason** when the server is not
 * reachable, so it does not fail a run that has no server; check `skipped=` in the
 * result XML, because a skip reads as green.
 *
 * ## What is real and what is stubbed
 *
 * Real: the check request, the manifest parse, the APK download (tens of megabytes),
 * and the SHA-256 verification. Stubbed: only the final install, because a
 * third-party app cannot install a package without the user tapping the system
 * installer — [RecordingInstaller] records that the verified APK reached the install
 * step instead.
 */
@RunWith(AndroidJUnit4::class)
class UpdateLiveE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val baseUrl = "http://10.0.2.2:3000"

    private fun serverReachable(): Boolean =
        try {
            OkHttpClient()
                .newCall(
                    okhttp3.Request
                        .Builder()
                        .url("$baseUrl/v1/version")
                        .get()
                        .build(),
                ).execute()
                .use { it.isSuccessful }
        } catch (t: Throwable) {
            false
        }

    @Before
    fun requireServer() {
        assumeTrue(
            "no OTA server at $baseUrl; start vyzorix-update-server on the host :3000",
            serverReachable(),
        )
    }

    /** Records that the verified APK reached the install step, without installing. */
    private class RecordingInstaller : ApkInstaller {
        var installed: File? = null

        override fun install(apk: File): Boolean {
            installed = apk

            return true
        }
    }

    @Test
    fun theUpdateMechanismRunsAgainstTheLiveServer() {
        val client = UpdateClient(baseUrl = baseUrl, http = OkHttpClient())

        // The server is publishing version_code 2; a device on code 1 must see an
        // update. This is the request the device makes on connect.
        val check = client.check(currentVersionCode = 1)
        assertTrue("the server must answer the update check", check != null)
        assertEquals("an update must be available for code 1", true, check!!.updateAvailable)
        assertTrue("the manifest must name an apk", check.apkFilename.isNotEmpty())
        assertTrue("the manifest must carry a checksum", check.apkSha256.isNotEmpty())

        // The whole pass, through the production checker.
        val installer = RecordingInstaller()
        val staging =
            File(context.cacheDir, "update-e2e").apply {
                deleteRecursively()
                mkdirs()
            }
        val checker =
            UpdateChecker(
                client = client,
                installer = installer,
                currentVersionCode = 1,
                stagingDir = staging,
            )

        checker.runOnce()

        val apk = installer.installed
        assertTrue("the verified apk must reach the install step", apk != null && apk.exists())
        assertEquals(
            "the staged apk must be the one the manifest named",
            check.apkFilename,
            apk!!.name,
        )
        // The size proves the download completed rather than truncated.
        assertTrue(
            "the downloaded apk must match the manifest size",
            apk.length() == check.apkSizeBytes,
        )
    }

    @Test
    fun aDeviceAlreadyOnTheLatestBuildDoesNotDownload() {
        val client = UpdateClient(baseUrl = baseUrl, http = OkHttpClient())

        // The server is on code 2; a device on code 2 must see no update, so the
        // download is not paid.
        val check = client.check(currentVersionCode = 2)
        assertTrue("the server must answer the update check", check != null)
        assertEquals(false, check!!.updateAvailable)

        val installer = RecordingInstaller()
        val checker =
            UpdateChecker(
                client = client,
                installer = installer,
                currentVersionCode = 2,
                stagingDir = File(context.cacheDir, "update-e2e-none"),
            )

        checker.runOnce()

        assertTrue("no update means no install", installer.installed == null)
    }
}
