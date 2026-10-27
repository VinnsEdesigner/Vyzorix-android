package com.vyzorix.compat

import android.os.Build

/**
 * Version gates for platform behaviour the broker depends on.
 *
 * Kept in one place so the version checks are auditable: each entry names what
 * changes and why the capability needs it. Scattered `Build.VERSION` checks are
 * how a feature silently breaks on one OS version.
 */
object ApiLevelCompat {

    /**
     * Whether this build can execute a binary from the app's own data directory.
     *
     * Android 10 denies `execve()` on files under an app's home directory for
     * apps targeting API 29+. The check is enforced through SELinux
     * (`seapp_contexts` maps `targetSdk >= 29` to `untrusted_app_29`, which lacks
     * the exec rule `untrusted_app_27` has), so it depends on the app's
     * *target* SDK, not the device's OS version.
     *
     * This app pins `targetSdk 28`, so the answer is true on every supported OS.
     * The function exists so a future targetSdk change fails loudly here rather
     * than at the first terminal launch.
     */
    fun canExecuteFromAppStorage(targetSdk: Int): Boolean = targetSdk <= 28

    /**
     * Whether PTY allocation is expected to succeed.
     *
     * `/dev/ptmx` is world-writable on every Android version this app supports,
     * so there is no version gate today. The function records that fact and
     * gives a place to add one if that ever changes.
     */
    fun canAllocatePty(): Boolean = true

    /**
     * Whether the OS requires a declared foreground service type.
     *
     * Android 14 requires one only for apps targeting API 34+; this app targets
     * 28, so it is not enforced. The manifest declares a type anyway, because it
     * is correct and costs nothing.
     */
    fun requiresForegroundServiceType(targetSdk: Int): Boolean = targetSdk >= 34

    /**
     * Whether the phantom-process killer is active.
     *
     * Android 12+ reaps orphaned child processes. Processes that are children of
     * a live foreground service are not affected, which is why the engine runs
     * under one.
     */
    fun hasPhantomProcessKiller(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}
