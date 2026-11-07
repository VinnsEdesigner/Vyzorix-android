package com.vyzorix.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.vyzorix.connector.update.ApkInstaller
import java.io.File

/**
 * Hands a verified APK to Android's package installer, the way an app is allowed to.
 *
 * This lives in `:app`, not `:cloud-connector`, because the install is an *app*
 * concern: it needs a `Context` to start an Activity and a `FileProvider` declared
 * in the app's manifest, and `androidx.core` (which provides `FileProvider`) is an
 * app dependency. The connector defines the [ApkInstaller] seam; the app supplies the
 * implementation, exactly as it does for the terminal port.
 *
 * ## Why the user has to confirm
 *
 * A third-party app cannot install a package silently. It launches an installer
 * intent (`ACTION_VIEW` on the APK with the package-archive MIME type) and the user
 * confirms. That is the OS rule, not a limitation here — a silent install needs a
 * system signature or root. So the OTA flow is automatic up to this point, and the
 * last tap is the user's.
 *
 * The file is exposed through a `FileProvider`: the installer runs in another process
 * and cannot read the app's private storage, and a `file://` URI throws
 * `FileUriExposedException` on a modern API. The provider's authority must match the
 * one declared in `AndroidManifest.xml` ([PROVIDER_AUTHORITY]).
 */
class AndroidApkInstaller(
    private val context: Context,
) : ApkInstaller {
    override fun install(apk: File): Boolean {
        if (!apk.exists()) {
            Log.w(TAG, "apk to install does not exist: ${apk.absolutePath}")

            return false
        }

        val uri =
            try {
                FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", apk)
            } catch (t: Throwable) {
                // A misconfigured provider (authority not declared) lands here.
                Log.e(TAG, "could not expose the apk through the file provider", t)

                return false
            }

        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                // The installer is a different process; without this it cannot read
                // the content URI.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Started from a Service, so a new task is required.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        return try {
            context.startActivity(intent)

            true
        } catch (t: Throwable) {
            Log.w(TAG, "no installer activity accepted the apk", t)

            false
        }
    }

    companion object {
        private const val TAG = "ApkInstaller"
        private const val APK_MIME = "application/vnd.android.package-archive"

        /**
         * The provider authority suffix. The full authority is the package name plus
         * this, which is what `AndroidManifest.xml` declares. Kept here so the two
         * cannot drift silently.
         */
        const val AUTHORITY_SUFFIX = ".fileprovider"
    }
}
