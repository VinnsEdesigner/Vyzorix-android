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
     * Asks the platform to install [apk].
     *
     * Returns true when the installer was launched (the user still has to confirm),
     * false when it could not be — for example on a device where the app may not
     * request package installs. A false return is not an exception: a device that
     * cannot self-update still runs, it just needs the APK sideloaded.
     */
    fun install(apk: File): Boolean
}
