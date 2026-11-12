package com.vyzorix.agent

import android.app.Application
import android.util.Log
import com.vyzorix.connector.CloudConnectionFactory
import com.vyzorix.container.ContainerConfig
import com.vyzorix.service.CloudLinkProvider
import com.vyzorix.service.TerminalShellPolicy

/**
 * Installs the cloud connection before anything else runs.
 *
 * The foreground service is started by the system from an `Intent`, so there is no
 * place to inject dependencies into it; `Application.onCreate` always runs first,
 * so the connection is built here and parked where the service can pick it up.
 *
 * The connection is built by `CloudConnectionFactory`, so this module never names
 * OkHttp or the format library — the app depends on the connector, not the other
 * way round.
 *
 * If the build carries no cloud configuration the connection is simply not
 * installed, and the device runs over the bridge alone — a valid state, not an
 * error.
 */
class VyzorixApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // The launcher icon tracks whether the agent is armed. Reconciling it here, at
        // process start, is what makes it survive the moments a one-shot assertion does
        // not: an OTA update resets the component state, and an OEM launcher may not
        // refresh its drawer when the component flips. Without this the icon could
        // "never hide" on a real device even though the switch was on.
        LauncherIcon.reconcile(this)

        // Tell the service whether a terminal may run a plain device shell. Off in a
        // production build: it runs as the app's uid, which the container prevents.
        TerminalShellPolicy.allowUncontainedShell(BuildConfig.ALLOW_UNCONTAINED_SHELL)

        // Park the container config where the service can read it. BuildConfig lives in
        // this module and the service is in :android-core, so the values have to be
        // handed across — the same shape as TerminalShellPolicy above.
        ContainerConfig.install(
            ContainerConfig(
                bundledAbi = BuildConfig.ROOTFS_ABI,
                sha256 = BuildConfig.ROOTFS_SHA256,
                extendedSha256 = BuildConfig.ROOTFS_EXTENDED_SHA256,
                baseUrl = BuildConfig.ROOTFS_BASE_URL,
                // the Vyzorix CLI is bundled when the build has the archive; when it does not,
                // the installer fetches it from the same release and verifies this.
                vyzorixCliSha256 = BuildConfig.VYZORIX_CLI_SHA256,
                // A plain URL stream. The rootfs is fetched once, from a release, and
                // the checksum is what makes it trustworthy — an HTTP client would add
                // a dependency without adding a guarantee here.
                open = { url -> java.net.URL(url).openStream() },
            ),
        )

        // The trust anchor is compiled in, and the build refuses to produce a release
        // APK without one (see app/build.gradle.kts). A debug build may carry none,
        // which is the deliberate bridge-only mode.
        val connection =
            try {
                CloudConnectionFactory.create(
                    context = this,
                    baseUrl = BuildConfig.CLOUD_BASE_URL,
                    serverPublicKeyPem = BuildConfig.SERVER_PUBLIC_KEY,
                    appVersion = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                    // The install step needs an Activity and a manifest provider, so it
                    // lives in this module and is handed across the seam.
                    apkInstaller = AndroidApkInstaller(this),
                )
            } catch (t: Throwable) {
                // A *malformed* anchor lands here, not the empty-anchor case (that
                // returns null above). It is a fault, not a mode: the device has an
                // anchor it cannot use, so it must not run half-configured. Log it
                // loudly and stay bridge-only rather than pretend the cloud works.
                Log.e(TAG, "cloud anchor is unusable; running bridge-only", t)

                null
            }

        CloudLinkProvider.install(connection)

        Log.i(TAG, "cloud connection installed=${connection != null}")
    }

    private companion object {
        const val TAG = "VyzorixApplication"
    }
}
