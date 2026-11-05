package com.vyzorix.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Installs the container's userspace on first boot: extract, verify, seed, confirm.
 *
 * ## When this runs
 *
 * Once, before the engine is started. [ProotLauncher.isReady] is the gate — the
 * launcher refuses to open a container shell until `rootfs/bin/bash` exists — so this
 * runs at service start and is a no-op on every boot after the first.
 *
 * ## Why the rootfs is bundled, not downloaded
 *
 * `debian-core` ships inside the APK under `assets/`. The foundation must work with
 * no network: a download that fails leaves the device with no userspace and no
 * fallback, and there is no store to push a fix through. The APK signature is the
 * integrity check, so no separate checksum is needed for this one. The optional
 * `debian-extended` tier is the one that downloads, and by the time it is wanted the
 * userspace already works, so a failed download is an inconvenience rather than a
 * brick.
 *
 * ## Why the overlay is seeded, not created empty
 *
 * `ProotLauncher.binds` binds `overlay/usr-local` and `overlay/opt` over the rootfs's
 * `/usr/local` and `/opt`. Those must exist as directories or proot's bind fails, so
 * the overlay is seeded here with the same shape. It survives a rootfs replacement,
 * which is the point: an update swaps `files/rootfs` and leaves installs alone.
 *
 * ## Idempotence, and what happens after a failure
 *
 * The install is only marked done once `bin/bash` is present and executable. A
 * failure — a truncated asset, no space — leaves no marker, so the next boot retries
 * from a clean rootfs rather than running a half-extracted one. A partial tree is
 * removed first, because "half a rootfs" is not a state to start a container in.
 */
class RootfsInstaller(
    private val context: Context,
    /** Where the bundled asset lives inside the APK. */
    private val assetName: String = DEFAULT_ASSET,
    /** The SHA-256 the archive must match. Empty disables the check (debug builds). */
    private val expectedSha256: String = "",
    /**
     * Fetches an archive when the APK does not bundle this device's architecture.
     *
     * Returns the bytes, or throws. Injected rather than built in so the installer has
     * no HTTP dependency of its own and can be tested without a server — the same
     * shape the connector uses for its clients.
     *
     * Null means "no fallback": the installer then fails rather than pretending. That
     * is what a build with no `ROOTFS_BASE_URL` gets.
     */
    private val download: ((url: String) -> InputStream)? = null,
    /** Where [download] fetches from. The release carrying every ABI's tarball. */
    private val baseUrl: String = "",
    /** This device's ABI, so the right tarball is fetched. */
    private val abi: String = "",
    /**
     * The SHA-256 the extended tarball must match.
     *
     * Empty disables the extended tier: it is downloaded, so unlike the bundled core
     * there is no APK signature over it, and an unverified download is an attacker's
     * userspace. Refusing to fetch without a checksum is the whole point.
     */
    private val extendedSha256: String = "",
) {
    /**
     * Installs the rootfs if it is not already installed. Returns whether the engine
     * is ready afterwards.
     *
     * Safe to call on every boot; the common path is a single `isReady` check.
     */
    fun ensureInstalled(): Boolean {
        if (ProotLauncher.isReady(context)) return true

        return try {
            install()

            true
        } catch (e: IOException) {
            // Not fatal: the device runs bridge-only until this succeeds, and the next
            // boot retries. Logged loudly because it is the difference between a
            // working container and a device that silently has no engine.
            //
            // No marker file is needed to make the retry correct: install() clears
            // whatever is at the rootfs path before extracting, so the next attempt
            // never resumes a tree of unknown completeness.
            Log.e(TAG, "rootfs install failed; engine unavailable this boot", e)

            false
        }
    }

    /**
     * Installs the optional extended tier (`git`, `python3`, `tmux`, ...), which is
     * always downloaded.
     *
     * Returns whether the extended tier is present afterwards. Safe to call on every
     * boot and on every terminal open: the common path is a marker-file check.
     *
     * ## Why this is separate from [ensureInstalled]
     *
     * The core tier is the foundation: bundled in the APK, installed before anything
     * can run, and a failure leaves the device bridge-only. The extended tier is
     * ~108 MB of tooling most devices never need, so it must not gate the engine, and
     * a failure must not look like a broken install. It is fetched on demand — when a
     * terminal opens, which is the first moment `tmux` matters — and a failure is
     * logged and forgotten; the next open retries.
     *
     * ## Extraction is into the same rootfs, and that is deliberate
     *
     * The extended packages are ordinary Debian files that belong at their FHS paths
     * (`/usr/bin/git`), so they extract over the core tree. That is not a "replacement
     * of the base" — the base and extended sets do not overlap in files, because the
     * extended build is `core + EXTENDED_PACKAGES` and dpkg lays each package out once.
     * A marker records completion so a half-extracted tier is not mistaken for a whole
     * one.
     */
    fun ensureExtended(): Boolean {
        if (isExtendedInstalled()) return true
        if (extendedSha256.isEmpty()) {
            // Not configured. Not an error: a debug build carries no checksum and
            // simply has no extended tier.
            Log.i(TAG, "extended tier not configured; skipping")

            return false
        }

        return try {
            installExtended()

            true
        } catch (e: IOException) {
            Log.w(TAG, "extended tier install failed; core engine is unaffected", e)

            false
        }
    }

    /**
     * True when the extended tier's marker exists.
     *
     * A marker, not a file probe: the extended set's contents may change between
     * releases, so "is `git` present" is the wrong question. The marker is written
     * only after a verified, complete extraction.
     */
    fun isExtendedInstalled(): Boolean = extendedMarker(context).isFile

    private fun installExtended() {
        val fetcher =
            download
                ?: throw IOException("no download configured for the extended tier")
        if (baseUrl.isEmpty() || abi.isEmpty()) {
            throw IOException("no rootfs download configured for $abi")
        }

        val url = "$baseUrl/$EXTENDED_PREFIX$abi$SUFFIX"
        Log.i(TAG, "fetching the $abi extended tier from $url")

        val rootfs = ProotLauncher.rootfs(context)

        // A stale marker from a previous failed attempt would make the next call
        // think the tier is whole, so it is cleared before extracting.
        extendedMarker(context).delete()

        fetcher(url).use { TarGz.extract(it, rootfs, extendedSha256) }

        if (!extendedMarker(context).createNewFile()) {
            throw IOException("could not write the extended-tier marker")
        }

        Log.i(TAG, "extended tier installed")
    }

    private fun install() {
        val rootfs = ProotLauncher.rootfs(context)

        // A partial tree from a previous failed attempt is not resumable.
        if (rootfs.exists() && !rootfs.deleteRecursively()) {
            throw IOException("could not clear the previous rootfs at ${rootfs.absolutePath}")
        }

        val bundled = openBundledAsset()
        if (bundled != null) {
            Log.i(TAG, "installing rootfs from the bundled asset $assetName")

            bundled.use { TarGz.extract(it, rootfs, expectedSha256) }
        } else {
            // The APK carries one architecture's rootfs; a device of another fetches
            // its own. This is the armeabi-v7a and x86_64 path.
            installFromRelease(rootfs)
        }

        seedOverlay()

        // proot's scratch dir. It is part of what isReady() checks, so setup has to
        // create it — and it is created HERE rather than by the readiness check,
        // because a check that repairs what it inspects can never report the problem.
        // (It used to, via tmpDir()'s mkdirs side effect, which hid this: the install
        // never made the directory and isReady() silently made it on every call.)
        val tmp = ProotLauncher.tmpDir(context)
        if (!tmp.isDirectory) {
            throw IOException("could not create proot's scratch dir ${tmp.absolutePath}")
        }

        // App storage, which proot binds to /home/apps. The source must exist before
        // the bind, or proot warns and skips it and /home/apps is then absent inside
        // the container — which the supervisor's `-w /home/apps` turns into a chdir
        // failure and an empty registry directory.
        val apps = ProotLauncher.appsDir(context)
        if (!apps.isDirectory && !apps.mkdirs()) {
            throw IOException("could not create app storage ${apps.absolutePath}")
        }

        // The one thing the engine cannot start without.
        if (!ProotLauncher.isReady(context)) {
            throw IOException(
                "rootfs extracted but bin/bash is missing or not executable; " +
                    "the archive is not usable",
            )
        }

        Log.i(TAG, "rootfs installed; engine ready")
    }

    /**
     * The bundled asset as a stream, or null when this build has none.
     *
     * `AssetManager` has no "exists" call — `list()` returns the immediate children of
     * a directory, and `open()` throws `FileNotFoundException` for a missing path. The
     * open is therefore the check, and its failure is the answer rather than an error.
     */
    private fun openBundledAsset(): InputStream? =
        try {
            context.assets.open(assetName, android.content.res.AssetManager.ACCESS_STREAMING)
        } catch (e: IOException) {
            // Not bundled. Either the build had no rootfs, or this device's ABI is not
            // the one that was bundled — both are handled by the download path.
            Log.i(TAG, "no bundled rootfs asset at $assetName: ${e.message}")

            null
        }

    /**
     * Downloads this device's rootfs from the release.
     *
     * The checksum is required here, unlike the bundled path: a bundled asset is
     * covered by the APK signature, but a download is bytes off the network. An
     * unverified rootfs is an attacker's userspace — the same argument as the pinned
     * server key — so a missing checksum is a refusal, not a warning.
     */
    private fun installFromRelease(rootfs: File) {
        val fetcher =
            download
                ?: throw IOException(
                    "this APK bundles the $abi rootfs but this device is not $abi, " +
                        "and no download is configured (ROOTFS_BASE_URL is unset)",
                )
        if (baseUrl.isEmpty() || abi.isEmpty()) {
            throw IOException("no rootfs download configured for $abi")
        }
        if (expectedSha256.isEmpty()) {
            throw IOException("refusing to install a downloaded rootfs with no checksum")
        }

        val url = "$baseUrl/$CORE_PREFIX$abi$SUFFIX"
        Log.i(TAG, "fetching the $abi rootfs from $url")

        fetcher(url).use { TarGz.extract(it, rootfs, expectedSha256) }
    }

    /**
     * Creates the overlay's bind targets.
     *
     * `binds()` names `overlay/usr-local` and `overlay/opt`, and proot fails a bind
     * whose source does not exist. Seeded here rather than lazily in the launcher so
     * the failure is at install time, where it can be reported, not at the first
     * shell.
     */
    private fun seedOverlay() {
        val overlay = ProotLauncher.overlay(context)

        for (name in OVERLAY_DIRS) {
            val dir = File(overlay, name)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("could not create overlay dir ${dir.absolutePath}")
            }
        }
    }

    companion object {
        private const val TAG = "RootfsInstaller"

        /**
         * The bundled rootfs asset.
         *
         * One name, no ABI in it: `assets/` is shared across ABIs — Android splits
         * only `jniLibs/` — so which architecture it is has to come from build config,
         * not the path.
         *
         * The extension is `.rootfs` rather than `.tar.gz` deliberately: AGP's
         * mergeAssets unpacks an asset it recognises as an archive, so a `.tar.gz`
         * asset arrives in the APK decompressed. The bytes are gzip either way; only
         * the name avoids AGP's archive handling.
         */
        const val DEFAULT_ASSET = "rootfs/debian-core.rootfs"

        /** The tarball names on the release, matching build-rootfs.sh's output. */
        const val CORE_PREFIX = "debian-core-"
        const val EXTENDED_PREFIX = "debian-extended-"
        const val SUFFIX = ".tar.gz"

        /**
         * Marks a complete extended-tier extraction.
         *
         * In `files/`, not the rootfs: a rootfs replacement must not carry a stale
         * "extended is installed" marker into a tree that has no extended files.
         */
        const val EXTENDED_MARKER = "extended.installed"

        fun extendedMarker(context: Context): File = File(context.filesDir, EXTENDED_MARKER)

        /**
         * The bind targets `ProotLauncher.binds` expects under the overlay.
         *
         * `usr-local` and `opt` bind over the rootfs's `/usr/local` and `/opt`;
         * `etc` binds to the container's `/etc/vyzorix` (architecture doc §6.1), the
         * config area that survives a rootfs replacement. All three must exist as
         * directories or proot fails the bind.
         */
        val OVERLAY_DIRS = listOf("usr-local", "opt", "etc")
    }
}
