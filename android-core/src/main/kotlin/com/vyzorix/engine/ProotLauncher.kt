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

    /** Where the bridge socket is bound inside the container. */
    const val BRIDGE_SOCKET_IN_CONTAINER = "/tmp/android.sock"

    /** The child reads the bridge socket path and token from these variables. */
    const val BRIDGE_SOCKET_ENV = "VYZORIX_BRIDGE_SOCKET"
    const val BRIDGE_TOKEN_ENV = "VYZORIX_BRIDGE_TOKEN"

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
     * The argument vector for a login shell inside Debian.
     *
     * `-0` fakes userspace root, which is what makes apt and dpkg behave. The
     * binds stitch the real Android filesystem into the container: `/dev`, `/proc`
     * and `/sys` for the kernel interfaces, `resolv.conf` for DNS (without it
     * there is no name resolution), the socket so the container can reach the
     * bridge, and the overlay prefix so on-device installs survive a rootfs
     * replacement.
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
        val rootfs = rootfs(context).absolutePath
        val overlay = overlay(context).absolutePath

        return listOf(
            prootBinary(context).absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r",
            rootfs,
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-b",
            "$rootfs/etc/resolv.conf:/etc/resolv.conf",
            "-b",
            "$overlay/usr-local:/usr/local",
            "-b",
            "$overlay/opt:/opt",
            "-b",
            "${socketPath(context).absolutePath}:$BRIDGE_SOCKET_IN_CONTAINER",
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
}
