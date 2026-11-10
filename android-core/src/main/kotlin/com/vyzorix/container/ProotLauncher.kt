package com.vyzorix.container

import android.content.Context
import android.os.Environment
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

    /**
     * App storage: the registry, the logs, and the apps' own files.
     *
     * Bound to `/home/apps` inside the container. It is **app storage, not package
     * storage**, and it is separate from the overlay for one reason that decides it:
     * Layer 2 is disposable and Layer 3's storage is not. The overlay holds
     * installed packages and is conceptually part of the container; this holds the
     * operator's apps and must survive anything the container does.
     */
    const val APPS_DIR = "apps"

    /** Where app storage appears inside the container. */
    const val APPS_IN_CONTAINER = "/home/apps"
    const val PROOT_LIBRARY = "libproot.so"

    /**
     * Shared storage, bound for a stock (tier 1) device.
     *
     * The device filesystem access notes record what an unrooted app can reach once the
     * storage permission is granted: the shared-storage root and the public
     * collections beneath it (Download, DCIM, Pictures, Music, Movies, Documents).
     * Binding the root exposes them all, so the collections are not named one by one.
     *
     * Binding a host path cannot grant the container more than the app already has.
     * proot does not change the real uid, so a container process runs as the app's
     * own uid, in the app's SELinux domain, under the same Unix permissions. The
     * bind changes what the container can *see*, never what the app may *touch*.
     *
     * This reverses the "optional export target, never a dependency" default: the
     * shared-storage tree is now bound on every start. It is one named constant so
     * the decision is visible and reversible in one place.
     *
     * The literal is a **guest** path — where the tree appears *inside* the container
     * — not a host path, which is why it is not derived from
     * `Environment.getExternalStorageDirectory()`. The host side of the bind is read
     * from the platform in [sharedStorageRoot].
     */
    @Suppress("SdCardPath") // A guest path, not a host one: lint cannot tell them apart.
    const val SHARED_STORAGE_IN_CONTAINER = "/sdcard"

    /**
     * Where the Android read-only trees appear inside the container.
     *
     * They are exposed under a **prefix**, never at their host paths. The container's
     * `/` is the Debian rootfs, which has its own `/bin`, `/usr`, `/lib`, `/etc`;
     * binding a host tree at `/system` would be harmless only by luck, and anything
     * landing on a shared top-level name silently shadows the Debian filesystem —
     * the exact failure the architecture warns about ("content [in a bind target] would be
     * shadowed and look like a bug"). A prefix makes the boundary explicit: these
     * are the device's files, read-only, over there.
     *
     * The trees are read-only because the app's uid cannot write them (SELinux and
     * the mount's own mode), **not** because proot enforces it — proot's `-b` has no
     * read-only flag. Do not describe this as a read-only bind in code or docs.
     *
     * They are also not runnable inside the container: `/system/lib64` is bionic, and
     * the guest is glibc Debian. This is for inspection and for the odd host file a
     * tool wants to read, not for executing Android binaries.
     */
    const val ANDROID_ROOT_IN_CONTAINER = "/android"

    /**
     * The Android trees exposed under [ANDROID_ROOT_IN_CONTAINER].
     *
     * Top-level only: `/system/lib64` is a child of `/system`, so binding the parent
     * already carries it and a second entry would be a redundant bind.
     *
     * A tree that does not exist on the device is skipped (see [androidBinds]), so a
     * device without `/oem` or `/product` gets a shorter list rather than a dead
     * entry — the same rule the shared-storage bind follows.
     */
    private val ANDROID_TREES = listOf("/system", "/vendor", "/product", "/odm", "/oem")

    /**
     * proot's scratch directory, app-private.
     *
     * proot writes a temporary file — the loader it embeds — before every exec, and
     * without this it fails with `can't create temporary file: No such file or
     * directory` because `P_tmpdir` (`/data/local/tmp`) is not writable by an app.
     * Found by running it on the emulator; nothing in the source says so.
     */
    const val TMP_DIR = "proot-tmp"

    /** The env var proot reads for [TMP_DIR]. It reads its *own* environment. */
    const val TMP_DIR_ENV = "PROOT_TMP_DIR"

    /**
     * proot's seccomp acceleration switch, read from proot's own environment.
     *
     * Termux proot accelerates syscall handling with a seccomp filter
     * (`SECCOMP_RET_TRACE`), which is the fast path on modern Android. On Android 9
     * (API 28) the kernel delivers the seccomp event at **sysexit** rather than
     * sysenter, and proot's handling of that order **segfaults the guest**: the
     * supervisor died with SIGSEGV the moment it was launched, so no app could ever
     * run (measured on the API 28 emulator). Disabling the acceleration makes proot
     * fall back to the plain `PTRACE_SYSCALL` path, which is correct on that kernel.
     *
     * It is gated to API <= 28 on purpose. On API 33 the acceleration is the working
     * path and this variable must NOT be set: with it, proot mishandles its own
     * syscall rewrites and `rename(2)` fails with ENOSYS (a measured upstream
     * defect). The two kernels need opposite settings,
     * so the setting is chosen by API level rather than fixed.
     */
    const val NO_SECCOMP_ENV = "PROOT_NO_SECCOMP"

    /**
     * The last API level whose kernel delivers the seccomp event at sysexit, where
     * proot's acceleration segfaults the guest (Android 9). See [NO_SECCOMP_ENV].
     */
    private const val LAST_OLD_ORDER_SECCOMP_API = 28

    /** True when proot must run without its seccomp acceleration on this device. */
    fun disablesSeccomp(): Boolean = android.os.Build.VERSION.SDK_INT <= LAST_OLD_ORDER_SECCOMP_API

    /** The environment proot itself must be started with, for this device's kernel. */
    fun prootEnv(context: Context): List<String> =
        buildList {
            add("$TMP_DIR_ENV=${tmpDir(context).absolutePath}")
            if (disablesSeccomp()) add("$NO_SECCOMP_ENV=1")
        }

    /** App-private directories that hold secrets and recordings, never bound. */
    const val SECRETS_DIR = "secrets"
    const val RECORDINGS_DIR = "recordings"

    /** Where the bridge socket is bound inside the container. */
    const val BRIDGE_SOCKET_IN_CONTAINER = "/tmp/android.sock"

    /**
     * The node compatibility prelude, inside the container, and the variable that
     * loads it for every node process.
     *
     * The prelude works around two environmental defects — an emulator CPU that
     * mis-emulates `cvttsd2si(NaN)` (so `Buffer.prototype.toString(enc)` throws, and
     * node cannot load any module from disk) and a proot rename that reports ENOENT
     * after it succeeded (which breaks npm's content store). Both make `npm` and
     * `vite` unusable without it. The file is `debian-container/rootfs/node-prelude.mjs`
     * and is installed into the rootfs; it is a no-op on a healthy host, so it is
     * loaded unconditionally.
     *
     * `--import` (not `--require`) because `NODE_OPTIONS` propagates to child
     * processes, and npm spawns node for lifecycle scripts — the vite build would
     * otherwise fail in its own child. An `.mjs` prelude runs before the entry
     * module, so the patch is in place before anything reads a file.
     */
    const val NODE_PRELUDE_IN_CONTAINER = "/usr/lib/vyzorix/node-prelude.mjs"

    /** The variable node reads for [NODE_PRELUDE_IN_CONTAINER]. */
    const val NODE_OPTIONS_ENV = "NODE_OPTIONS"

    /** The child reads the bridge socket path and token from these variables. */
    const val BRIDGE_SOCKET_ENV = "VYZORIX_BRIDGE_SOCKET"
    const val BRIDGE_TOKEN_ENV = "VYZORIX_BRIDGE_TOKEN"

    /**
     * The sandbox zone: app-private directories that must never be bound into the
     * container. Only the secret store and the recordings qualify.
     *
     * `rootfs` is deliberately NOT here: it is the container's own root (`-r
     * rootfs`), so binding a file out of it is how proot presents the root, not a
     * leak. `android.sock` is not here either — it is the door, and the rule exists so
     * the container can reach it. The overlay is the writable prefix the container
     * is supposed to write to.
     *
     * What must never be bound is anything that holds a secret or a recording. The
     * closed-world bind list achieves that by naming only what is allowed; this
     * list is the second, independent check.
     */
    private val SANDBOX_ZONE_DIRS = listOf(SECRETS_DIR, RECORDINGS_DIR)

    /** Path proot is installed at, once the native library is extracted. */
    fun prootBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, PROOT_LIBRARY)

    fun rootfs(context: Context): File = File(context.filesDir, ROOTFS_DIR)

    fun overlay(context: Context): File = File(context.filesDir, OVERLAY_DIR)

    fun appsDir(context: Context): File = File(context.filesDir, APPS_DIR)

    fun socketPath(context: Context): File = File(context.filesDir, "android.sock")

    /**
     * The shared-storage root bound into the container, or null when the device has
     * none.
     *
     * Read from the platform rather than hardcoded: `Environment.getExternalStorageDirectory()`
     * is `/sdcard` on a stock device, but a device with a real removable card or a
     * non-default mount can differ, and the app must bind what the app can actually
     * reach — the same source `ExternalStorageApi` reads.
     *
     * Null (no external storage, or it does not exist) means the bind is skipped
     * rather than passed through. A `-b` naming a path that is not there is not
     * fatal to proot, but it would put a dead entry in every argv and misreport the
     * container's world; skipping it keeps the bind list equal to what exists.
     */
    fun sharedStorageRoot(): String? =
        Environment.getExternalStorageDirectory()?.takeIf { it.exists() }?.absolutePath

    /**
     * The `-b` entries exposing the Android read-only trees under
     * [ANDROID_ROOT_IN_CONTAINER].
     *
     * Each tree that exists is bound to `<prefix>/<basename>`: `/system` becomes
     * `/android/system`. A tree that is absent is skipped, so the list matches what
     * the device actually has.
     *
     * The guest target's parent directory does not exist in the rootfs. proot creates
     * missing bind targets as needed (it is a path rewrite, not a mount), so no
     * rootfs change is required — but that also means an *empty* source would appear
     * as an empty directory rather than an error, which is why existence is checked
     * here rather than left to proot.
     */
    fun androidBinds(): List<String> =
        androidBindsFor(ANDROID_TREES.filter { File(it).isDirectory })

    /**
     * The mapping from existing trees to `-b` entries, split out so it is testable.
     *
     * [androidBinds] cannot be asserted on the JVM — there is no `/system` there, so
     * it returns an empty list and a test over it proves nothing. This takes the
     * already-filtered list, so the mapping is exercised with real inputs.
     */
    internal fun androidBindsFor(trees: List<String>): List<String> =
        trees.map { "$it:$ANDROID_ROOT_IN_CONTAINER/${File(it).name}" }

    /**
     * proot's scratch dir, created.
     *
     * A getter that *makes* the directory. That is deliberate for the callers that
     * need it to exist (building the environment), but it is why [isReady] must not
     * use it: a readiness check that repairs the thing it is checking can never
     * report it missing, so it would claim the container is ready and proot would then
     * fail with "can't create temporary file". [tmpDirPath] is the side-effect-free
     * form, and [isReady] uses that.
     */
    fun tmpDir(context: Context): File = tmpDirPath(context).apply { mkdirs() }

    /** proot's scratch dir, without creating it. For checks, not for setup. */
    fun tmpDirPath(context: Context): File = File(context.filesDir, TMP_DIR)

    /**
     * True when the container can be started at all.
     *
     * Three things, and the third is the one that is easy to miss: the binary must be
     * executable, the rootfs must hold `bin/bash`, and proot's scratch dir must be
     * writable. Without the last, proot reports `can't create temporary file` on the
     * first exec — a failure that looks like a broken rootfs but is not.
     */
    fun isReady(context: Context): Boolean =
        prootBinary(context).canExecute() &&
            File(rootfs(context), "bin/bash").exists() &&
            tmpDirPath(context).isDirectory

    /**
     * True when [hostPath] is inside the app's sandbox zone.
     *
     * The launcher's final assertion: even if a path were
     * added to the bind list by mistake, a sandbox-zone path is refused. Two
     * independent guarantees — the closed-world list and this check — so one
     * mistake does not open the zone.
     */
    fun isSandboxZonePath(hostPath: String): Boolean {
        val normalized = File(hostPath).path

        return SANDBOX_ZONE_DIRS.any { dir ->
            val marker = File.separator + dir
            normalized.endsWith(marker) || normalized.contains("$marker${File.separator}")
        }
    }

    /**
     * The `-b host:guest` bind list for the container.
     *
     * This is a closed world: only the paths named here are bound, and
     * [isSandboxZonePath] refuses any sandbox-zone path before it becomes an
     * argument. The container therefore cannot reach the rootfs, the secret store
     * or the recordings by construction, not by remembering to exclude them.
     */
    fun binds(context: Context): List<String> {
        val rootfs = rootfs(context).absolutePath
        val overlay = overlay(context).absolutePath

        val binds =
            buildList {
                // Kernel interfaces the container needs.
                add("/dev")
                add("/proc")
                add("/sys")
                // DNS, or there is no name resolution inside the container.
                add("$rootfs/etc/resolv.conf:/etc/resolv.conf")
                // The writable overlay prefix, so on-device installs survive a
                // rootfs replacement. Only named subdirectories, never the overlay
                // root and never files/.
                add("$overlay/usr-local:/usr/local")
                add("$overlay/opt:/opt")
                // The config area that survives a rootfs replacement. The
                // target is /etc/vyzorix, a directory the rootfs does not ship, so
                // the overlay is the only thing there — which is the point: an
                // update swaps files/rootfs and leaves on-device config alone.
                add("$overlay/etc:/etc/vyzorix")
                // App storage: Layer 2 is disposable, Layer 3's storage is not.
                // The supervisor's registry, its logs and the apps' files live here,
                // so a rootfs replacement leaves every app and its data alone.
                add("${appsDir(context).absolutePath}:$APPS_IN_CONTAINER")
                // Shared storage, so the container can read and write the folders a
                // stock device exposes: the public collections (Download, DCIM,
                // Pictures, Music, Movies, Documents) and `Android/media`. The
                // app-private tree is NOT bound — see the note below. Skipped when
                // the device has no shared storage, so the list equals what exists.
                sharedStorageRoot()?.let { add("$it:$SHARED_STORAGE_IN_CONTAINER") }
                // The Android read-only trees, under a prefix so they cannot shadow
                // the Debian filesystem. Present only if the device has them.
                addAll(androidBinds())
                // The bridge socket, so the container can reach the API bridge. The
                // socket file only, not the directory that holds the secret store.
                add("${socketPath(context).absolutePath}:$BRIDGE_SOCKET_IN_CONTAINER")
            }

        // The app's own private tree (`context.filesDir`, i.e. /data/user/0/<pkg>)
        // is deliberately NOT bound. It holds the rootfs, the overlay, the secret
        // store and the recordings — the sandbox zone — so binding it would hand the
        // container the keys to itself. Only named subdirectories cross (apps, the
        // socket); the tree as a whole never does. `isSandboxZonePath` is the second,
        // independent check on the same rule.
        //
        // Shared storage is safe to bind wholesale: proot does not change the real
        // uid, so a container process runs as the app's own uid, in the app's SELinux
        // domain, under the same Unix permissions. The bind changes what the
        // container can *see*, never what the app may *touch* — `/sdcard/Android/data`
        // and other apps' collections stay denied to the container exactly as they
        // are denied to the app.
        //
        // This reverses the "optional export target, never a dependency": shared
        // storage is now bound on every start. `SHARED_STORAGE_IN_CONTAINER` is the
        // single place that decision lives.

        // Assert the invariant rather than trusting the list above. A path that is
        // not sandbox-zone but is also not one of the expected shapes is still
        // allowed; what is refused is the zone.
        val offending = binds.firstOrNull { isSandboxZonePath(it.substringBefore(":")) }
        check(offending == null) { "sandbox-zone path in bind list: $offending" }

        return binds
    }

    /**
     * The argument vector for a login shell inside Debian.
     *
     * `-0` fakes userspace root, which is what makes apt and dpkg behave. The
     * binds come from [binds], which is the closed world: the container gets the
     * kernel interfaces, DNS, the overlay prefix and the socket, and nothing in the
     * sandbox zone.
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
        /**
         * Wrap the shell in `tmux` under this session name, or null for a bare shell.
         *
         * A **stable** name is what makes a dropped WebSocket reattach: `tmux
         * new-session -A -s NAME` creates the session the first time and *attaches*
         * to it afterwards, so a client that reconnects with the same name finds its
         * shell still running instead of starting a new one. The name is the caller's
         * — it must be the same across reconnects and different between terminals,
         * which is exactly what a terminal session id is.
         *
         * Null is the default and the fallback: `tmux` is in the extended tier, which
         * a device may not have, and a shell without reattach is better than no shell.
         */
        tmuxSession: String? = null,
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
                "$NODE_OPTIONS_ENV=--import $NODE_PRELUDE_IN_CONTAINER",
                "$BRIDGE_SOCKET_ENV=$BRIDGE_SOCKET_IN_CONTAINER",
                "$BRIDGE_TOKEN_ENV=$token",
            )

        if (tmuxSession != null) {
            // tmux must be told its socket directory explicitly, or it refuses to
            // start with "no suitable socket path" and the terminal is dead.
            //
            // tmux's compiled default is the list `"$TMUX_TMPDIR:" _PATH_TMP`, i.e.
            // `/tmp/` with a *trailing slash*, and `expand_paths` drops any entry
            // whose `realpath(3)` fails. Under proot that call fails: `realpath` on a
            // path with a trailing slash returns ENOENT (proot's path translation
            // mishandles the empty final component), even though the directory
            // plainly exists — `stat`, `lstat` and `access` all succeed on it. With
            // the only entry dropped, tmux finds no socket path and exits 1.
            //
            // Setting `TMUX_TMPDIR=/tmp` (no slash) makes the first entry resolve, so
            // tmux uses `/tmp/tmux-0/<name>`. This is an environment fix, not a
            // `/tmp` workaround: `/tmp` is already the intended location.
            args += "TMUX_TMPDIR=$TMUX_TMPDIR_IN_CONTAINER"

            // `new-session -A -s NAME` is the reattach: create NAME, or attach if it
            // exists. The shell is given explicitly rather than left to tmux's default
            // (`$SHELL`, which `env -i` has just cleared), so the container always
            // gets `bash --login` whether it is the first attach or the tenth.
            //
            // One consequence worth knowing: the tmux *server* outlives any single
            // client, and it keeps the environment of the client that started it. A
            // reconnect therefore reuses the original session's bridge token rather
            // than the new one. That is the desired behaviour — the shell keeps the
            // session it was opened under — but it means a token rotation does not
            // reach an already-running shell.
            args +=
                listOf(
                    TMUX_BINARY,
                    "new-session",
                    "-A",
                    "-s",
                    tmuxSession,
                    "/bin/bash",
                    "--login",
                )
        } else {
            args += listOf("/bin/bash", "--login")
        }

        return args
    }

    /**
     * True when `tmux` is present in the rootfs.
     *
     * `tmux` ships in the extended tier, which is fetched on demand, so a device may
     * not have it. The caller checks this rather than assuming, so a terminal opened
     * before the extended tier lands gets a plain shell instead of a failed exec.
     */
    fun hasTmux(context: Context): Boolean = File(rootfs(context), "usr/bin/tmux").canExecute()

    /**
     * The full request to run a login shell on a fresh PTY, with the bridge token
     * in its environment.
     */
    fun loginShellRequest(
        context: Context,
        token: String,
        tmuxSession: String? = null,
    ): PtyRequest =
        PtyRequest(
            executable = prootBinary(context).absolutePath,
            arguments = loginShellArgs(context, token, tmuxSession = tmuxSession),
            // The outer environment — what proot itself is started with. It is
            // deliberately NOT in loginShellArgs' `env -i` list: that is the
            // *container's* environment, and proot reads PROOT_TMP_DIR before it ever
            // execs the shell. Putting it there would be too late.
            environment =
                listOf(
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                ) + prootEnv(context),
        )

    /**
     * The request that starts the Layer 3 supervisor inside the container.
     *
     * Not a PTY session: the supervisor is a daemon, not a terminal. It is run with
     * the same proot argv shape as the login shell, minus the shell — `env -i` to
     * clear the environment, then the supervisor binary, which is installed into the
     * rootfs by [Layer3Installer].
     *
     * Its stdout and stderr are the caller's to capture: the supervisor logs to
     * stderr, and Layer 1 relays that to logcat so `logcat` shows supervisor output
     * without a second log path.
     *
     * `--apps`, `--logs` and `--socket` are passed explicitly rather than left to the
     * binary's defaults, so the paths are stated in one place (here) and the
     * defaults are only a fallback for a hand-run supervisor.
     */
    fun supervisorArgs(context: Context): List<String> {
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
                APPS_IN_CONTAINER,
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "TERM=dumb",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                SUPERVISOR_BINARY,
                "--apps",
                "$APPS_IN_CONTAINER/apps.json",
                "--logs",
                "$APPS_IN_CONTAINER/logs",
                "--socket",
                "$APPS_IN_CONTAINER/supervisor.sock",
            )

        return args
    }

    /**
     * True when the supervisor binary is installed.
     *
     * It lives in the **overlay**, not the rootfs: `/usr/local` is a bind target of
     * the overlay, so `overlay/usr-local/bin/supervisor` is what the container
     * sees at `/usr/local/bin/supervisor`. Checking under the rootfs would look
     * plausible and always be false.
     *
     * Like [hasTmux], this is checked rather than assumed: [Layer3Installer] may not
     * have run yet on a device upgrading in place.
     */
    fun hasSupervisor(context: Context): Boolean =
        File(File(overlay(context), OVERLAY_USR_LOCAL), SUPERVISOR_IN_OVERLAY).canExecute()

    /**
     * A plain device shell, for when the container is not installed.
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

    /**
     * `tmux` inside the container, absolute.
     *
     * It ships in the extended tier, so it may be absent; [hasTmux] is the check.
     */
    const val TMUX_BINARY = "/usr/bin/tmux"

    /**
     * The socket directory handed to `tmux` as `TMUX_TMPDIR`.
     *
     * Deliberately `/tmp` and **not** `/tmp/`: tmux canonicalises its socket path
     * list with `realpath(3)`, which under proot fails on a trailing slash, and its
     * compiled default is `/tmp/`. A path without the slash resolves, so tmux starts.
     */
    const val TMUX_TMPDIR_IN_CONTAINER = "/tmp"

    /**
     * The supervisor binary inside the container, absolute.
     *
     * Installed into the overlay's `/usr/local/bin` by the Layer 3 installer, so it
     * survives a rootfs replacement the same way the app storage does.
     */
    const val SUPERVISOR_BINARY = "/usr/local/bin/supervisor"

    /** The overlay subdirectory bound over the container's `/usr/local`. */
    const val OVERLAY_USR_LOCAL = "usr-local"

    /** The supervisor's path within that directory. */
    const val SUPERVISOR_IN_OVERLAY = "bin/supervisor"
}
