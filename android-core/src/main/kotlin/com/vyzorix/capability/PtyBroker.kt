package com.vyzorix.capability

import android.util.Log

/**
 * Allocates PTYs and starts processes on them.
 *
 * The PTY is created here, by the app, not by anything inside proot — that is
 * what makes terminal support independent of what the container can see. The
 * broker holds the master fd; the Debian side receives a duplicate over a socket
 * when it needs one (see `com.vyzorix.capability.fd`).
 *
 * Callers are responsible for reading the master fd on a single thread. This
 * class does not run a reader loop; see [PtySession] for the threading contract.
 */
class PtyBroker(
    private val registry: PtySessionRegistry = PtySessionRegistry(),
) {

    /** Allocates a PTY and starts [request]. The returned session owns the fds. */
    fun open(sessionId: String, deviceId: String, operatorId: String, request: PtyRequest): PtyResult<PtySession> {
        val loaded = NativePty.loadLibrary()
        if (loaded is PtyResult.Err) {
            return loaded
        }

        val pair = NativePty.openPty()
        if (!pair.isOk) {
            return PtyResult.Err(PtyError.fromNative(pair.status, pair.detail))
        }

        val masterFd = pair.fds?.getOrNull(0) ?: -1
        val slaveFd = pair.fds?.getOrNull(1) ?: -1
        if (masterFd < 0 || slaveFd < 0) {
            return PtyResult.Err(
                PtyError(PtyError.Kind.INTERNAL, "openPty returned no descriptors"),
            )
        }

        val spawn = NativePty.spawnOnPty(
            masterFd = masterFd,
            slaveFd = slaveFd,
            path = request.executable,
            argv = request.arguments.toTypedArray(),
            env = request.environment.toTypedArray(),
            workingDir = request.workingDirectory,
            rows = request.rows,
            cols = request.cols,
        )

        if (!spawn.isOk) {
            // The fork failed, so no child exists. Release both fds here; the
            // session object that would own them was never created.
            NativePty.closeFd(masterFd)
            return PtyResult.Err(PtyError.fromNative(spawn.status, spawn.detail))
        }

        val pid = spawn.fds?.getOrNull(0) ?: -1
        if (pid <= 0) {
            NativePty.closeFd(masterFd)
            return PtyResult.Err(PtyError(PtyError.Kind.INTERNAL, "spawnOnPty returned no pid"))
        }

        // The parent side of the slave is already closed by SpawnOnPty; holding
        // it open would stop the master reporting EOF when the child dies.
        val session = PtySession(
            id = sessionId,
            masterFd = masterFd,
            slaveFd = -1,
            slavePath = pair.detail,
            childPid = pid,
            deviceId = deviceId,
            operatorId = operatorId,
        )

        val registered = registry.register(session)
        if (registered is PtyResult.Err) {
            // Cap reached or duplicate id: tear the child down rather than
            // leaving an orphaned process with no session pointing at it.
            session.close()
            session.releaseFd()
            return registered
        }

        Log.i(TAG, "session $sessionId opened on $deviceId (pid $pid, ${pair.detail})")

        return PtyResult.Ok(session)
    }

    /** Closes a session and returns it. The reader must call releaseFd afterwards. */
    fun close(sessionId: String): PtyResult<PtySession> = registry.unregister(sessionId)

    /** Closes every session for a device. */
    fun closeAllForDevice(deviceId: String): List<PtySession> =
        registry.unregisterAllForDevice(deviceId)

    /** Sessions idle longer than [idleMs]. */
    fun idleSessions(idleMs: Long): List<PtySession> = registry.idleSessions(idleMs)

    val sessionCount: Int get() = registry.count

    private companion object {
        const val TAG = "PtyBroker"
    }
}

/** What to run on a new PTY. */
data class PtyRequest(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val environment: List<String> = emptyList(),
    val workingDirectory: String = "",
    val rows: Int = 24,
    val cols: Int = 80,
)
