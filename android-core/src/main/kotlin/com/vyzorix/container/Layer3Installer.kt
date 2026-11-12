package com.vyzorix.container

import android.content.Context
import android.content.res.AssetManager
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
 * The rootfs is the container's userspace — a Debian tree, replaced wholesale. These
 * are *our* binaries, built from this repo for the container's glibc, and they live
 * in the writable overlay's `/usr/local/bin` rather than in the rootfs. That
 * placement is the point: `/usr/local` is a bind target of the overlay, so a
 * rootfs replacement leaves the supervisor and the CLI in place, exactly as it
 * leaves app storage in place. Putting them in the rootfs would delete them on the
 * next container update.
 *
 * ## Where the archive comes from
 *
 * Bundled in the APK under `assets/`, or — when the build does not bundle it — fetched
 * from the release that carries `layer3-<abi>.tar.gz`. The bundled copy is the fast
 * path and the offline path; the download lets a build ship without the ~10 MB asset,
 * so a rebuilt container reaches a device as a new release rather than only as a new
 * APK.
 *
 * A **fetched** archive is verified against the build's baked [layer3Sha256]. That
 * checksum is the integrity anchor: unlike the bundled copy, a download has no APK
 * signature over it, so an unverified download is an attacker's binary. An empty
 * checksum therefore disables the download path rather than fetching unverified.
 *
 * ## Idempotence
 *
 * A marker records what was installed. It is the ABI plus the archive's length (for a
 * bundled archive) or its checksum (for a fetched one), so a rebuilt container
 * reinstalls while an unchanged one is a no-op.
 */
class Layer3Installer(
    private val context: Context,
    /** The asset holding this build's binaries. */
    private val assetName: String = DEFAULT_ASSET,
    /**
     * Fetches an archive when the APK does not bundle it.
     *
     * Null means "no fallback": a build that bundles the asset needs none. Injected
     * rather than built in, so the installer has no HTTP dependency of its own and its
     * tests need no server — the same shape [RootfsInstaller] uses.
     */
    private val download: ((url: String) -> InputStream)? = null,
    /** Where [download] fetches from. The release carrying every ABI's tarball. */
    private val baseUrl: String = "",
    /** This device's ABI, so the right tarball is fetched. */
    private val abi: String = "",
    /**
     * The SHA-256 a fetched archive must match.
     *
     * Empty disables the download path: a fetched archive with no checksum is an
     * attacker's code, so it is refused rather than trusted.
     */
    private val expectedSha256: String = "",
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
            // container, the bridge and the terminal all still work. Logged loudly
            // because it is the difference between "apps run" and "apps silently do
            // not", and the next boot retries.
            Log.e(TAG, "layer 3 binaries failed to install; apps will not run", e)

            false
        }
    }

    /** True when the supervisor is installed and the marker matches this build. */
    fun isInstalled(): Boolean =
        ProotLauncher.hasSupervisor(context) && marker(context).readTextOrNull() == installKey()

    /** The recorded install marker for this build, or "" when none is written. */
    fun installedMarker(): String = marker(context).readTextOrNull().orEmpty()

    /** The install marker this build would write. Differs from [installedMarker] when stale. */
    fun expectedMarker(): String = installKey()

    /**
     * What the marker records: the ABI plus a value that changes when the binaries do.
     *
     * For a bundled archive that value is the archive's **length**. The ABI alone is
     * not enough — a rebuilt supervisor with the same ABI has the same name, so an
     * ABI-only marker made every rebuild a no-op. The length changes when the binaries
     * change. (Reading it needs an uncompressed asset: `assets/layer3/layer3.l3` is in
     * `noCompress`, because `openFd` has no fd for a deflated asset and the length would
     * read as -1 — a constant, and the bug that left a device on old commands.)
     *
     * For a fetched archive it is the checksum, which is a stronger signal than a
     * length: it is the exact build the release published.
     */
    private fun installKey(): String {
        if (hasBundledAsset()) return "$abi:${assetLength()}"

        return "$abi:$expectedSha256"
    }

    private fun install() {
        val overlay = ProotLauncher.overlay(context)
        // The archive's paths are relative to /usr/local, so it extracts at the
        // overlay's usr-local — which is what is bound over the container's
        // /usr/local.
        val target = File(overlay, OVERLAY_SUB_DIR)

        if (!target.isDirectory && !target.mkdirs()) {
            throw IOException("could not create ${target.absolutePath}")
        }

        // Prefer the bundled copy: it needs no network, and the APK signature covers
        // it. Fall back to the release only when the build does not bundle it.
        if (hasBundledAsset()) {
            installFromAsset(target)
        } else {
            installFromRelease(target)
        }

        if (!ProotLauncher.hasSupervisor(context)) {
            throw IOException(
                "the archive extracted but ${ProotLauncher.SUPERVISOR_IN_OVERLAY} is missing " +
                    "or not executable",
            )
        }

        marker(context).writeText(installKey())

        Log.i(TAG, "layer 3 binaries installed")
    }

    /** Extracts the bundled asset. The APK signature is the integrity check. */
    private fun installFromAsset(target: File) {
        Log.i(TAG, "installing layer 3 binaries from the bundled asset into ${target.absolutePath}")

        val asset =
            try {
                context.assets.open(assetName, AssetManager.ACCESS_STREAMING)
            } catch (e: IOException) {
                throw IOException("no bundled layer 3 archive at $assetName", e)
            }

        asset.use { TarGz.extract(it, target, expectedSha256 = "") }
    }

    /**
     * Fetches the archive from the release and extracts it, verified.
     *
     * The checksum is required: a fetch with no expected hash is refused rather than
     * trusted, because there is no APK signature over a downloaded archive.
     */
    private fun installFromRelease(target: File) {
        val fetcher = download ?: throw IOException("no download configured for layer 3")
        if (baseUrl.isEmpty() || abi.isEmpty()) {
            throw IOException("no layer 3 download configured for $abi")
        }
        if (expectedSha256.isEmpty()) {
            // Refusing here is the point: an unverified download is arbitrary code, and
            // the build should either bake the asset or carry the checksum.
            throw IOException("layer 3 download refused: no checksum to verify against")
        }

        val url = "$baseUrl/$LAYER3_PREFIX$abi$LAYER3_SUFFIX"
        Log.i(TAG, "fetching layer 3 binaries from $url")

        fetcher(url).use { TarGz.extract(it, target, expectedSha256) }
    }

    /** True when this build bundles the archive. */
    @Suppress("SwallowedException")
    private fun hasBundledAsset(): Boolean =
        try {
            context.assets.open(assetName).use { true }
        } catch (e: IOException) {
            // A missing asset is the "not bundled" answer, not an error: the download
            // path is the fallback for exactly this build.
            false
        }

    /**
     * The bundled archive's byte length, or -1 when it cannot be read.
     *
     * A compressed asset has no fd and an absent one throws; both mean "unknown",
     * which is distinct from a real length so the marker changes once the asset is
     * readable.
     */
    @Suppress("SwallowedException")
    private fun assetLength(): Long =
        try {
            context.assets.openFd(assetName).use { it.length }
        } catch (e: Exception) {
            -1
        }

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

        /** Marks what was installed, so a re-install is a no-op. */
        const val MARKER = "layer3.installed"

        /** The release asset's name, `layer3-<abi>.tar.gz`. */
        const val LAYER3_PREFIX = "layer3-"
        const val LAYER3_SUFFIX = ".tar.gz"
    }
}
