package com.vyzorix.service

import android.content.Context
import com.vyzorix.engine.EngineConfig
import com.vyzorix.engine.RootfsInstaller

/**
 * Installs the container's userspace, whoever needs it first.
 *
 * ## Why this is not just `RootfsInstaller`
 *
 * `RootfsInstaller` needs the build's values — which ABI is bundled, the checksum,
 * where to fetch from — and those arrive through [EngineConfig] because `BuildConfig`
 * belongs to `:app` and a library cannot read it. This object is the one place that
 * pairs the two, so a caller does not have to know the pairing.
 *
 * ## Why it exists at all
 *
 * The service used to be the only thing that could install the rootfs, because the
 * installer was a private field on it. That made the engine untestable without
 * service lifecycle timing, and made the install unreachable from anywhere else — a
 * second caller would have had to duplicate the wiring or reach into the service.
 * It is a small, idempotent operation; it belongs somewhere both can call.
 *
 * Idempotent and cheap when already installed: the first thing it does is ask
 * [com.vyzorix.engine.ProotLauncher.isReady], which is three filesystem checks.
 */
object RootfsBootstrap {
    /**
     * Ensures the engine's userspace is installed. Returns whether it is ready
     * afterwards.
     *
     * Safe to call from anywhere, on any thread — it does blocking I/O, so not the
     * main thread. The service calls it from its start thread; a test calls it
     * directly.
     */
    fun ensureInstalled(context: Context): Boolean {
        val config = EngineConfig.get()

        val installer =
            RootfsInstaller(
                context = context,
                // No config installed is a valid state: no bundled asset is found and
                // no fallback is configured, so the install reports false and the
                // device runs bridge-only.
                expectedSha256 = config?.sha256.orEmpty(),
                download = config?.open,
                baseUrl = config?.baseUrl.orEmpty(),
                abi = config?.bundledAbi.orEmpty(),
            )

        return installer.ensureInstalled()
    }
}
