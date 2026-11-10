package com.vyzorix.service

/**
 * Whether the terminal may run a shell outside the Debian container.
 *
 * The architecture's terminal is the container's login shell: proot gives the
 * session a filesystem boundary, so the shell cannot read the app's private files
 * (the device identity, the config, the recordings). Until the rootfs asset ships
 * that shell cannot start at all, and an operator gets an empty terminal.
 *
 * This flag allows a plain device shell instead. It is **off by default** because it
 * is a real weakening: a shell running as the app's own uid can read everything the
 * app can, which the container exists to prevent. A development build may turn it on
 * to exercise the terminal before Layer 2 ships; production must not.
 *
 * Kept as a settable holder rather than a constructor argument for the same reason
 * [CloudLinkProvider] is: the service is created by the system from an `Intent`, so
 * the app has to park the value somewhere the service can read it.
 */
object TerminalShellPolicy {
    @Volatile
    private var allowUncontained = false

    fun allowUncontainedShell(allow: Boolean) {
        allowUncontained = allow
    }

    fun isUncontainedShellAllowed(): Boolean = allowUncontained
}
