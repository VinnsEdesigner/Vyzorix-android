package com.vyzorix.engine

import java.io.InputStream

/**
 * Where the engine's userspace comes from, and how to verify it.
 *
 * ## Why this exists rather than reading `BuildConfig`
 *
 * `BuildConfig` belongs to `:app`. A library module cannot read the application's
 * generated config — it is a different module's build output — so the values have to
 * be handed across. The app parks them here in `Application.onCreate`, the same shape
 * [com.vyzorix.service.CloudLinkProvider] and
 * [com.vyzorix.service.TerminalShellPolicy] already use, because the service is
 * created by the system from an `Intent` and there is nowhere to inject into.
 *
 * ## What the values mean
 *
 * An APK bundles **one** architecture's rootfs: `assets/` is shared across ABIs
 * (Android splits only `jniLibs/`), so a universal APK cannot carry one per ABI.
 * [bundledAbi] records which one it is; a device of another ABI fetches its own from
 * [baseUrl], verified against [sha256].
 */
data class EngineConfig(
    /** The ABI whose rootfs this APK bundles. */
    val bundledAbi: String,
    /** SHA-256 the archive must match. Empty means "nothing to verify against". */
    val sha256: String,
    /**
     * SHA-256 the **extended** tier must match when it is fetched.
     *
     * The extended tier always downloads — it is never bundled, because it is the
     * ~108 MB optional tooling (`git`, `python3`, `tmux`) that a shell-only device
     * never runs — so unlike [sha256] there is no APK signature covering it. A
     * downloaded rootfs with no checksum is an attacker's userspace, so an empty
     * value here means the extended tier is unavailable rather than unverified.
     */
    val extendedSha256: String,
    /** Where a device of another ABI fetches its rootfs. Empty disables the fallback. */
    val baseUrl: String,
    /**
     * Fetches a URL as a stream.
     *
     * Injected so the installer holds no HTTP client of its own — the same reason the
     * connector takes its clients as arguments. It also means the installer's tests
     * need no server.
     */
    val open: (url: String) -> InputStream,
) {
    companion object {
        /**
         * The configuration the service reads.
         *
         * Defaults to "nothing bundled, no fallback", which is a valid state: the
         * device runs bridge-only. A build with a rootfs sets this before the service
         * starts.
         */
        @Volatile
        private var installed: EngineConfig? = null

        fun install(config: EngineConfig?) {
            installed = config
        }

        fun get(): EngineConfig? = installed
    }
}
