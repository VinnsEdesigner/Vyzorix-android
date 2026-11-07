package com.vyzorix.connector.update

import android.util.Log
import java.io.File

/**
 * Drives the OTA flow: check for a newer build, download it, verify it, and hand it
 * to the installer.
 *
 * It runs on connect — the same trigger as telemetry — so a device that has been
 * offline for a while checks as soon as it can reach the server. It is not on a
 * timer: the check is cheap, but the download is not, and a device that connects
 * rarely should check when it connects, not sit in a poll loop.
 *
 * ## What "automatic" means here
 *
 * The device performs the whole flow automatically up to the install. The install
 * itself is the platform's decision: an unrooted app cannot install a package
 * without the user confirming it, so the last step is an installer intent the user
 * taps. That is not a gap in this code — it is Android's rule, and pretending
 * otherwise (a background install) is exactly what the OS forbids. So the mechanism
 * is automatic; the consent is the user's.
 */
class UpdateChecker(
    private val client: UpdateClient,
    private val installer: ApkInstaller,
    private val currentVersionCode: Int,
    /** Where the downloaded APK is staged before install. */
    private val stagingDir: File,
    /** Called with the manifest when a newer build exists, for logging/telemetry. */
    private val onUpdateFound: (UpdateCheck) -> Unit = {},
) {
    /**
     * One full pass: check, and if there is a newer build, download and install it.
     *
     * Never throws: an update failure must not take the connection down. Every
     * outcome is logged, and a failure simply means the device keeps running the
     * build it has.
     */
    fun runOnce() {
        val check =
            client.check(currentVersionCode) ?: run {
                Log.d(TAG, "update check unavailable; will retry on next connect")

                return
            }

        if (!check.updateAvailable) {
            Log.i(TAG, "up to date (running $currentVersionCode, server ${check.versionCode})")

            return
        }

        onUpdateFound(check)
        Log.i(TAG, "update available: ${check.version} (code ${check.versionCode})")

        val apk = File(stagingDir, check.apkFilename)
        try {
            client.download(check, apk)
        } catch (t: Throwable) {
            // A failed or unverified download is not fatal; the next connect retries.
            Log.w(TAG, "update download failed: ${t.message}")

            return
        }

        val launched = installer.install(apk)
        if (launched) {
            Log.i(TAG, "installer launched for ${check.version}")
        } else {
            // The device cannot install packages itself (no installer permission, or
            // the user declined). The verified APK is staged; an operator can install
            // it, and the device keeps running its current build.
            Log.w(TAG, "could not launch the installer; apk staged at ${apk.absolutePath}")
        }
    }

    private companion object {
        const val TAG = "UpdateChecker"
    }
}
