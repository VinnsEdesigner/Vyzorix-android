package com.vyzorix.engine

import android.content.Context
import com.vyzorix.capability.PtyRequest
import java.io.File

/**
 * Builds the proot command line.
 *
 * The PTY is allocated by Layer 1 and the slave becomes this process's stdio, so
 * proot is exec'd on an already-open tty rather than allocating one itself. That
 * is the whole point of the fd-capability model: the container never opens
 * `/dev/ptmx`.
 */
object ProotLauncher {

    /** Where the rootfs and the writable overlay live, both app-private. */
    const val ROOTFS_DIR = "rootfs"
    const val OVERLAY_DIR = "overlay"
    const val PROOT_LIBRARY = "libproot.so"

    /** App-private directories that hold secrets and recordings, never bound. */
    const val SECRETS_DIR = "secrets"
    const val RECORDINGS_DIR = "recordings"

    /** Where the bridge socket is bound inside the container. */
    const val BRIDGE_SOCKET_IN_CONTAINER = "/tmp/android.sock"

    /** The child reads the bridge socket path and token from these variables. */
    const val BRIDGE_SOCKET_ENV = "VYZORIX_BRIDGE_SOCKET"
    const val BRIDGE_TOKEN_ENV = "VYZORIX_BRIDGE_TOKEN"

    /**
     * The engine zone: app-private directories that must never be bound into the
     * container. Only the secret store and the recordings qualify.
     *
     * `rootfs` is deliberately NOT here: it is the container's own root (`-r
     * rootfs`), so binding a file out of it is how proot presents the root, not a
     * leak. `android.sock` is not here either — it is the door, and §2.2 exists so
     * the container can reach it. The overlay is the writable prefix the container
     * is supposed to write to.
     *
     * What must never be bound is anything that holds a secret or a recording. The
     * closed-world bind list achieves that by naming only what is allowed; this
     * list is the second, independent check.
     */
    private val ENGINE_ZONE_DIRS = listOf(SECRETS_DIR, RECORDINGS_DIR)

    /** Path proot is installed at, once the native library is extracted. */
    fun prootBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, PROOT_LIBRARY)

    fun rootfs(context: Context): File = File(context.filesDir, ROOTFS_DIR)

    fun overlay(context: Context): File = File(context.filesDir, OVERLAY_DIR)

    fun socketPath(context: Context): File = File(context.filesDir, "android.sock")

    /** True when the engine can be started at all. */
    fun isReady(context: Context): Boolean =
        prootBinary(context).canExecute() && File(rootfs(context), "bin/bash").exists()

    /**
     * True when [hostPath] is inside the app's engine zone.
     *
     * The launcher's final assertion (boundary contract §10): even if a path were
     * added to the bind list by mistake, an engine-zone path is refused. Two
     * independent guarantees — the closed-world list and this check — so one
     * mistake does not open the zone.
     */
    fun isEngineZonePath(hostPath: String): Boolean {
        val normalized = File(hostPath).path

        return ENGINE_ZONE_DIRS.any { dir ->
            val marker = File.separator + dir
            normalized.endsWith(marker) || normalized.contains("$marker${File.separator}")
        }
    }

    /**
     * The `-b host:guest` bind list for the container.
     *
     * This is a closed world: only the paths named here are bound, and
     * [isEngineZonePath] refuses any engine-zone path before it becomes an
     * argument. The container therefore cannot reach the rootfs, the secret store
     * or the recordings by construction, not by remembering to exclude them.
     */
    fun binds(context: Context): List<String> {
        val rootfs = rootfs(context).absolutePath
        val overlay = overlay(context).absolutePath

        val binds =
            listOf(
                // Kernel interfaces the container needs.
                "/dev",
                "/proc",
                "/sys",
                // DNS, or there is no name resolution inside the container.
                "$rootfs/etc/resolv.conf:/etc/resolv.conf",
                // The writable overlay prefix, so on-device installs survive a
                // rootfs replacement. Only named subdirectories, never the overlay
                // root and never files/.
                "$overlay/usr-local:/usr/local",
                "$overlay/opt:/opt",
                // The bridge socket, so the container can reach the API bridge. The
                // socket file only, not the directory that holds the secret store.
                "${socketPath(context).absolutePath}:$BRIDGE_SOCKET_IN_CONTAINER",
            )

        // Assert the invariant rather than trusting the list above. A path that is
        // not engine-zone but is also not one of the expected shapes is still
        // allowed; what is refused is the zone.
        val offending = binds.firstOrNull { isEngineZonePath(it.substringBefore(":")) }
        check(offending == null) { "engine-zone path in bind list: $offending" }

        return binds
    }

    /**
     * The argument vector for a login shell inside Debian.
     *
     * `-0` fakes userspace root, which is what makes apt and dpkg behave. The
     * binds come from [binds], which is the closed world: the container gets the
     * kernel interfaces, DNS, the overlay prefix and the socket, and nothing in the
     * engine zone.
     *
     * The bridge token is placed in the **inner** environment — the one
     * `/usr/bin/env -i` builds — not the outer one proot is started with. `env -i`
     * clears the environment, so a token passed only in the outer environment
     * would be wiped before the shell ever saw it. This is the whole point of the
     * env-var transport: the secret is in the child's environment, never in argv,
     * where `ps` would show it.
     */
    fun loginShellArgs(
        context: Context,
        token: String,
        workingDir: String = "/root",
    ): List<String> {
        val args =
            mutableListOf(
                prootBinary(context).absolutePath,
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                rootfs(context).absolutePath,
            )

        for (bind in binds(context)) {
            args += "-b"
            args += bind
        }

        args +=
            listOf(
                "-w",
                workingDir,
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "$BRIDGE_SOCKET_ENV=$BRIDGE_SOCKET_IN_CONTAINER",
                "$BRIDGE_TOKEN_ENV=$token",
                "/bin/bash",
                "--login",
            )

        return args
    }

    /**
     * The full request to run a login shell on a fresh PTY, with the bridge token
     * in its environment.
     */
    fun loginShellRequest(
        context: Context,
        token: String,
    ): PtyRequest =
        PtyRequest(
            executable = prootBinary(context).absolutePath,
            arguments = loginShellArgs(context, token),
            environment =
                listOf(
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                ),
        )

    /**
     * A plain device shell, for when the container engine is not installed.
     *
     * This is **not** the architecture's terminal: it runs as the app's own uid, so
     * it can read the app's private files, which the container exists to prevent. It
     * exists only so the terminal path can be exercised before Layer 2 ships, and the
     * caller must gate it on [com.vyzorix.service.TerminalShellPolicy].
     *
     * The bridge socket is bound to the app's real path here rather than the
     * container's `/tmp/android.sock`, because there is no container to translate it.
     */
    fun uncontainedShellRequest(
        context: Context,
        token: String,
    ): PtyRequest =
        PtyRequest(
            executable = UNCONTAINED_SHELL,
            arguments = listOf(UNCONTAINED_SHELL, "-i"),
            environment =
                listOf(
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                    "HOME=${context.filesDir.absolutePath}",
                    "$BRIDGE_SOCKET_ENV=${socketPath(context).absolutePath}",
                    "$BRIDGE_TOKEN_ENV=$token",
                ),
        )

    /** The device's own shell, used only by [uncontainedShellRequest]. */
    const val UNCONTAINED_SHELL = "/system/bin/sh"
}
