package com.vyzorix.capability

/**
 * The JNI surface. Declarations only — no logic.
 *
 * Every function returns [NativePtyResult] rather than throwing, so a native
 * failure cannot unwind into the JVM. Kotlin callers should use [PtyBroker],
 * which wraps these into [PtyResult].
 *
 * The library is loaded once per process; [loadLibrary] is idempotent so callers
 * need not coordinate.
 *
 * A JNI surface is one function per native entry point, and the signatures are
 * fixed by the C side. Splitting or shortening them to satisfy a lint rule would
 * cause a mismatch at runtime (UnsatisfiedLinkError), which no lint rule is worth.
 */
@Suppress("TooManyFunctions", "LongParameterList")
internal object NativePty {

    private const val LIBRARY = "vyzorix_core"

    @Volatile
    private var loaded = false

    /** Loads the native library, or returns the failure reason. */
    fun loadLibrary(): PtyResult<Unit> =
        synchronized(this) {
            if (loaded) {
                return@synchronized PtyResult.Ok(Unit)
            }
            return try {
                System.loadLibrary(LIBRARY)
                loaded = true
                PtyResult.Ok(Unit)
            } catch (t: UnsatisfiedLinkError) {
                PtyResult.Err(
                    PtyError(PtyError.Kind.INTERNAL, "loadLibrary($LIBRARY): ${t.message}"),
                )
            }
        }

    /**
     * Allocates a PTY pair.
     *
     * On success [NativePtyResult.fds] holds `[master, slave]` and
     * [NativePtyResult.detail] holds the slave path.
     */
    external fun openPty(): NativePtyResult

    /**
     * Forks and execs [path] on the slave end of the PTY.
     *
     * On success [NativePtyResult.fds] holds `[pid]`.
     */
    external fun spawnOnPty(
        masterFd: Int,
        slaveFd: Int,
        path: String,
        argv: Array<String>,
        env: Array<String>,
        workingDir: String,
        rows: Int,
        cols: Int,
    ): NativePtyResult

    /**
     * Reads from the master fd, waiting at most [timeoutMs].
     *
     * Returns with [NativePtyResult.timedOut] set when nothing arrived in time —
     * this is not an error. A blocking read is never used, because an idle shell
     * would hang the calling thread.
     */
    external fun readMaster(
        fd: Int,
        maxBytes: Int,
        timeoutMs: Int,
    ): NativePtyResult

    /** Writes to the master fd. */
    external fun writeMaster(
        fd: Int,
        data: ByteArray,
    ): NativePtyResult

    /** Applies a terminal geometry via TIOCSWINSZ. */
    external fun setWindowSize(
        fd: Int,
        rows: Int,
        cols: Int,
    ): NativePtyResult

    /** Sends SIGKILL and reaps. Safe on an already-dead pid. */
    external fun killChild(pid: Int): NativePtyResult

    /**
     * Reaps without blocking.
     *
     * On success [NativePtyResult.fds] holds `[reaped ? 1 : 0, exitCode]`. A
     * negative exit code means the child died from that signal.
     */
    external fun reapChild(pid: Int): NativePtyResult

    /**
     * Natively exercises the SCM_RIGHTS path end to end: socketpair, send a real
     * descriptor, receive it, and verify the copy works. On success
     * [NativePtyResult.detail] is `"received=<n>, roundtrip=ok"`.
     */
    external fun selfTestFdPassing(): NativePtyResult

    /** Closes a raw fd. Idempotent: an already-closed fd is not an error. */
    external fun closeFd(fd: Int): NativePtyResult

    /** Sends fds over a connected unix socket via SCM_RIGHTS. */
    external fun sendFds(
        socketFd: Int,
        fds: IntArray,
    ): NativePtyResult

    /** Receives fds from a connected unix socket. */
    external fun receiveFds(
        socketFd: Int,
        timeoutMs: Int,
    ): NativePtyResult

    /**
     * Creates, binds and listens on an AF_UNIX stream socket in the **filesystem**
     * namespace at [path], returning the listening fd in
     * [NativePtyResult.fds][0]. Used by the bridge, which needs a filesystem path
     * so proot can bind the socket into the container (contract §2.2); Android's
     * `LocalServerSocket(String)` can only bind the abstract namespace.
     *
     * A stale node at [path] is removed first.
     */
    external fun bindListenSocket(path: String): NativePtyResult
}
