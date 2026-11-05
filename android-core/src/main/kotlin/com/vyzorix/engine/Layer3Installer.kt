package com.vyzorix.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Installs the Layer 3 container binaries: the supervisor, the `app-*` CLI, and the
 * bridge client.
 *
 * ## Why this is separate from [RootfsInstaller]
 *
 * The rootfs is the engine's userspace — a Debian tree, replaced wholesale. These
 * are *our* binaries, built from this repo for the container's glibc, and they live
 * in the writable overlay's `/usr/local/bin` rather than in the rootfs. That
 * placement is the point: `/usr/local` is a bind target of the overlay (§6.1), so a
 * rootfs replacement leaves the supervisor and the CLI in place, exactly as it
 * leaves app storage in place. Putting them in the rootfs would delete them on the
 * next engine update.
 *
 * ## Where the archive comes from
 *
 * Bundled in the APK under `assets/`, one per ABI, staged by the app's build. It is
 * small (~360 KB) and it is *our* code, so unlike the rootfs there is no reason to
 * fetch it: the APK signature covers it, and a device with no network can still run
 * apps.
 *
 * ## Idempotence
 *
 * A marker records the installed ABI. Re-running with the same ABI is a no-op; a
 * different ABI (an app update to a different architecture, which cannot happen for
 * one install, but a restored backup could) replaces the tree.
 */
class Layer3Installer(
    private val context: Context,
    /** The asset holding this build's binaries. */
    private val assetName: String = DEFAULT_ASSET,
) {
    /**
     * Installs the binaries if they are not already there for this ABI.
     *
     * Returns whether the supervisor is present afterwards. Safe to call on every
     * boot: the common path is a marker check.
     */
    fun ensureInstalled(): Boolean {
        if (isInstalled()) return true

        return try {
            install()

            true
        } catch (e: IOException) {
            // Not fatal: a device without the supervisor runs no apps, but the
            // engine, the bridge and the terminal all still work. Logged loudly
            // because it is the difference between "apps run" and "apps silently do
            // not", and the next boot retries.
            Log.e(TAG, "layer 3 binaries failed to install; apps will not run", e)

            false
        }
    }

    /** True when the supervisor is installed for this ABI. */
    fun isInstalled(): Boolean =
        ProotLauncher.hasSupervisor(context) && marker(context).readTextOrNull() == abi

    private fun install() {
        val overlay = ProotLauncher.overlay(context)
        // The archive's paths are relative to /usr/local, so it extracts at the
        // overlay's usr-local — which is what is bound over the container's
        // /usr/local.
        val target = File(overlay, OVERLAY_SUB_DIR)

        if (!target.isDirectory && !target.mkdirs()) {
            throw IOException("could not create ${target.absolutePath}")
        }

        val asset =
            try {
                context.assets.open(assetName, android.content.res.AssetManager.ACCESS_STREAMING)
            } catch (e: IOException) {
                throw IOException("no bundled layer 3 archive at $assetName", e)
            }

        Log.i(TAG, "installing layer 3 binaries from $assetName into ${target.absolutePath}")

        // No checksum: the archive is a bundled asset, so the APK signature is the
        // integrity check — the same argument the core rootfs tier makes.
        asset.use { TarGz.extract(it, target, expectedSha256 = "") }

        if (!ProotLauncher.hasSupervisor(context)) {
            throw IOException(
                "the archive extracted but ${ProotLauncher.SUPERVISOR_IN_ROOTFS} is missing " +
                    "or not executable",
            )
        }

        marker(context).writeText(abi)

        Log.i(TAG, "layer 3 binaries installed")
    }

    /** This device's ABI, which is the archive's name. */
    private val abi: String
        get() = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    private fun marker(context: Context): File = File(context.filesDir, MARKER)

    private fun File.readTextOrNull(): String? = if (isFile) readText().trim() else null

    companion object {
        private const val TAG = "Layer3Installer"

        /**
         * The bundled archive.
         *
         * One name, no ABI in it: `assets/` is shared across ABIs — Android splits
         * only `jniLibs/` — so the build stages the right one, exactly as the rootfs
         * asset works. The `.l3` extension is deliberate: AGP unpacks an asset it
         * recognises as an archive, so a `.tar.gz` arrives decompressed. The bytes
         * are gzip either way; only the name avoids AGP's handling.
         */
        const val DEFAULT_ASSET = "layer3/layer3.l3"

        /** The overlay subdirectory the archive's `bin/` sits under. */
        const val OVERLAY_SUB_DIR = "usr-local"

        /** Marks the installed ABI, so a re-install is a no-op. */
        const val MARKER = "layer3.installed"
    }
}
