package com.vyzorix.connector.update

import java.io.File

/**
 * Hands a verified APK to the platform's package installer.
 *
 * A seam, because the install is the one step that cannot be done silently: a
 * third-party app cannot install a package without the user confirming it (Android
 * requires `REQUEST_INSTALL_PACKAGES` and an installer intent the user taps). Making
 * it an interface keeps the orchestration — check, download, verify — testable
 * without an Activity, and keeps the "what happens next" honest: on an unrooted
 * device the update *waits for the user*, it does not apply itself.
 *
 * The real implementation launches the installer intent; a test implementation
 * records that it was asked.
 */
interface ApkInstaller {
    /**
     * Asks the platform to install [apk], which is version [version] of the app.
     *
     * Returns true when an install path was offered to the user: either the installer
     * was launched directly, or a notification was posted whose tap opens it. False
     * when neither was possible — for example the user has not allowed this app to
     * install unknown apps. A false return is not an exception: a device that cannot
     * self-update still runs, it just needs the APK sideloaded.
     *
     * The version is passed rather than read from the APK because the notification
     * names it, and parsing an APK's manifest for a label is not worth the code.
     */
    fun install(
        apk: File,
        version: String,
    ): Boolean
}
