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
    /** Progress 0–100 while downloading, or -1 when the size is unknown. */
    private val onProgress: (UpdateCheck, Int) -> Unit = { _, _ -> },
    /** Called once the APK is downloaded and verified, before the installer is launched. */
    private val onDownloaded: (UpdateCheck) -> Unit = {},
    /** Called after the installer intent is launched: the app is about to restart. */
    private val onInstalling: (UpdateCheck) -> Unit = {},
    /**
     * Called when the server reports no update is available — the device is already
     * running the latest build. The caller uses this to clear the "update ready to
     * install" notification, which is the signal that a pending install finally
     * landed. It is *not* called on a failed or unavailable check, so a transient
     * network failure cannot dismiss a prompt the user still needs.
     */
    private val onUpToDate: () -> Unit = {},
) {
    /**
     * The version code of the last build for which the install was offered.
     *
     * `runOnce` fires on **every** WebSocket reconnect. Without this, a device whose
     * install cannot complete — the common case on Android 10+, where the install
     * waits for a user tap that never comes — re-downloaded the whole APK on every
     * reconnect. On a flaky link that is the reconnect cadence times ~73 MB: a real
     * device pulled a gigabyte in under two hours this way. The same version is never
     * offered twice.
     */
    @Volatile
    private var offeredVersionCode: Int = 0

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

            // A confirmed up-to-date check is the one moment the device can prove the
            // offered install landed: the server no longer has anything newer. Clear
            // the install prompt here, so the Accept install button goes away only
            // once the update is really applied.
            if (check.versionCode <= currentVersionCode) {
                onUpToDate()
            }

            return
        }

        // Already offered this exact build and the install did not happen (the user
        // has not tapped yet, or declined). Re-offering means re-downloading tens of
        // megabytes on every reconnect, which is the data leak this guards. The staged
        // APK is still on disk; the user installs it when they are ready.
        if (check.versionCode == offeredVersionCode) {
            Log.d(TAG, "update ${check.version} already offered; not re-downloading")

            return
        }

        onUpdateFound(check)
        Log.i(TAG, "update available: ${check.version} (code ${check.versionCode})")

        val apk = File(stagingDir, check.apkFilename)

        // Reuse the staged APK when it is already there and still matches the
        // manifest's hash: a download that completed on an earlier connect must not be
        // paid for again. `download` verifies the checksum, so a stale or corrupt file
        // is caught there and re-fetched.
        val alreadyStaged = apk.isFile && client.matches(check, apk)
        if (!alreadyStaged) {
            try {
                client.download(check, apk) { percent -> onProgress(check, percent) }
            } catch (t: Throwable) {
                // A failed or unverified download is not fatal; the next connect retries.
                Log.w(TAG, "update download failed: ${t.message}")

                return
            }
        } else {
            Log.i(TAG, "update ${check.version} already staged; skipping the download")
        }

        onDownloaded(check)

        // Record the offer *before* the install attempt: whatever the outcome — the
        // installer opened, a notification was posted, or the grant is missing — this
        // build has been presented, and re-presenting it means re-downloading.
        offeredVersionCode = check.versionCode

        val offered = installer.install(apk, check.version)
        if (offered) {
            Log.i(TAG, "install offered for ${check.version}")
            onInstalling(check)
        } else {
            // The device cannot install packages itself (the user has not allowed this
            // app to install unknown apps). The verified APK is staged; a user can
            // install it, and the device keeps running its current build.
            Log.w(TAG, "could not offer the install; apk staged at ${apk.absolutePath}")
        }
    }

    private companion object {
        const val TAG = "UpdateChecker"
    }
}
