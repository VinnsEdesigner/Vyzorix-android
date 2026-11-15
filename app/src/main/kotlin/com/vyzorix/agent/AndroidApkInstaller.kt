package com.vyzorix.agent

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.vyzorix.connector.update.ApkInstaller
import com.vyzorix.service.DeviceNotification
import java.io.File

/**
 * Hands a verified APK to Android's package installer, the way an app is allowed to.
 *
 * This lives in `:app`, not `:cloud-connector`, because the install is an *app*
 * concern: it needs a `Context` to post a notification and a `FileProvider` declared
 * in the app's manifest, and `androidx.core` (which provides both) is an app
 * dependency. The connector defines the [ApkInstaller] seam; the app supplies the
 * implementation, exactly as it does for the terminal port.
 *
 * ## Why this posts a notification instead of just starting the installer
 *
 * The obvious implementation — `startActivity(ACTION_VIEW)` on the APK — **does not
 * work from a background service**. Android 10+ blocks background activity starts, and
 * this app is headless: its only foreground state is a service, so the installer
 * intent was silently dropped and the download appeared to do nothing. (The emulator's
 * `userdebug` build was more permissive, which is why it looked fine there and not on
 * a real device.)
 *
 * The fix is the one Android actually supports for this case: the agent posts a
 * notification whose content intent opens the installer. When the user taps it the
 * launch is **user-initiated**, which is always allowed, and the install proceeds. The
 * notification *is* the "tap to update" step — it is not a workaround so much as the
 * only route the OS offers a headless app.
 *
 * ## Why the notification carries an "Accept install" action and never goes away
 *
 * The installer's own confirmation can be dismissed by accident, and once it is gone
 * there is nothing to tap — the APK is staged, but the user has no control left. So the
 * notification is posted *before* the direct-start attempt, is **ongoing** (it cannot
 * be swiped away), and carries an explicit **Accept install** action that re-opens the
 * installer for the same staged, verified APK. It is cleared only once the install is
 * confirmed — the device comes back reporting the newer version (see
 * `DeviceNotification.clearInstall`). Until then the button is always there to retry.
 *
 * ## Why the user still confirms
 *
 * Even once the installer opens, a third-party app cannot install silently; the user
 * confirms on the system screen. That is the OS rule, not a limitation here — a silent
 * install needs a system signature or root. So the flow is automatic up to the
 * installer, and the confirmation is the user's.
 *
 * The APK is exposed through a `FileProvider`: the installer runs in another process
 * and cannot read the app's private storage, and a `file://` URI throws
 * `FileUriExposedException` on a modern API. The provider's authority must match the
 * one declared in `AndroidManifest.xml` ([AUTHORITY_SUFFIX]).
 */
class AndroidApkInstaller(
    private val context: Context,
) : ApkInstaller {
    override fun install(
        apk: File,
        version: String,
    ): Boolean {
        if (!apk.exists()) {
            Log.w(TAG, "apk to install does not exist: ${apk.absolutePath}")

            return false
        }

        // On Android 8+ the user must have allowed this app to install unknown apps
        // (REQUEST_INSTALL_PACKAGES). Without it the installer opens and immediately
        // refuses, which reads to the user as "the update did nothing". So the
        // grant is checked here and, when it is missing, the user is sent to the
        // screen that grants it instead of a dead installer.
        if (!canInstall()) {
            Log.w(TAG, "not allowed to install packages; sending the user to the setting")
            openInstallPermissionSettings()

            return true
        }

        val uri =
            try {
                FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", apk)
            } catch (t: Throwable) {
                // A misconfigured provider (authority not declared) lands here.
                Log.e(TAG, "could not expose the apk through the file provider", t)

                return false
            }

        // Always post the "Accept install" notification first. It is the *durable*
        // retry path: the system installer's prompt can be dismissed by accident, and
        // without a standing notification there is then no way to bring it back. The
        // notification is ongoing and carries an Accept install action, so the user
        // can re-open the installer as many times as it takes.
        val posted = postInstallNotification(uri, version)

        // Try the direct start as well: if the OS happens to permit it (a device where
        // the agent is in the foreground, or a permissive build), the installer opens
        // with one fewer tap. A `BackgroundActivityStartDeniedException` /
        // `ActivityNotFoundException` is the expected outcome on a background service,
        // and is not an error — the notification above is what the user taps.
        if (startInstaller(uri)) {
            Log.i(TAG, "installer launched directly")

            return true
        }

        return posted
    }

    /** True when the user has allowed this app to install packages. */
    private fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Launches the system installer for [uri]. False when the start is blocked. */
    private fun startInstaller(uri: Uri): Boolean {
        val intent = installerIntent(uri)

        return try {
            context.startActivity(intent)

            true
        } catch (t: Throwable) {
            // Android 10+ blocks this from a background service; the notification path
            // below is the fix, so this is logged at debug, not as a failure.
            val kind = t.javaClass.simpleName
            Log.d(TAG, "direct installer start blocked ($kind); using a notification")

            false
        }
    }

    /** Posts the "tap to install" notification. Returns whether it was posted. */
    private fun postInstallNotification(
        uri: Uri,
        version: String,
    ): Boolean {
        // Unlike the foreground service's own notification, this second one is *not*
        // exempt from POST_NOTIFICATIONS, so on API 33+ it needs the grant. Without it
        // there is no way to offer the install, so this is a real (reported) failure.
        if (!notificationsAllowed()) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; cannot offer the install")

            return false
        }

        val pending =
            PendingIntent.getActivity(
                context,
                INSTALL_REQUEST_CODE,
                installerIntent(uri),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            DeviceNotification(context).installReady(version, pending)

        return try {
            NotificationManagerCompat
                .from(context)
                .notify(DeviceNotification.INSTALL_NOTIFICATION_ID, notification)

            true
        } catch (t: SecurityException) {
            // Belt and braces with the check above: the grant can be revoked between
            // the check and the post.
            Log.w(TAG, "could not post the install notification", t)

            false
        }
    }

    /** True when a second (non-foreground) notification may be posted. */
    private fun notificationsAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** The installer intent for [uri]: ACTION_VIEW on the APK, with read permission. */
    private fun installerIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            // The installer is a different process; without this it cannot read the
            // content URI.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Started from a Service (or a notification), so a new task is required.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Opens the per-app "install unknown apps" screen. */
    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "could not open the unknown-sources setting", it) }
    }

    companion object {
        private const val TAG = "ApkInstaller"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val INSTALL_REQUEST_CODE = 2

        /**
         * The provider authority suffix. The full authority is the package name plus
         * this, which is what `AndroidManifest.xml` declares. Kept here so the two
         * cannot drift silently.
         */
        const val AUTHORITY_SUFFIX = ".fileprovider"
    }
}
