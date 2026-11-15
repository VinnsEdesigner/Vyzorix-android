package com.vyzorix.capability

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Allocates PTYs and starts processes on them.
 *
 * The PTY is created here, by the app, not by anything inside proot — that is
 * what makes terminal support independent of what the container can see.
 *
 * A session is identified on the wire by a **channel**: a small integer allocated
 * on open and freed on close. Deriving it from the session id would let two ids
 * collide, and one session would then answer for the other.
 *
 * This class does not read the master fd. See [PtyReader] for that, and for the
 * ownership rule that only the reader closes it.
 */
class PtyBroker(
    private val registry: PtySessionRegistry = PtySessionRegistry(),
) {
    private val channels = ConcurrentHashMap<Int, PtySession>()

    /**
     * Allocates a PTY, starts [request], and assigns a channel.
     *
     * @return the channel number on success.
     */
    fun open(
        sessionId: String,
        deviceId: String,
        userId: String,
        request: PtyRequest,
    ): PtyResult<Int> {
        val loaded = NativePty.loadLibrary()
        if (loaded is PtyResult.Err) return loaded

        val pair = NativePty.openPty()
        if (!pair.isOk) return PtyResult.Err(PtyError.fromNative(pair.status, pair.detail))

        val masterFd = pair.fds?.getOrNull(0) ?: -1
        val slaveFd = pair.fds?.getOrNull(1) ?: -1
        if (masterFd < 0 || slaveFd < 0) {
            return PtyResult.Err(
                PtyError(PtyError.Kind.INTERNAL, "openPty returned no descriptors"),
            )
        }

        val spawn =
            NativePty.spawnOnPty(
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
            // The fork failed, so no child exists and no session will own the fd.
            NativePty.closeFd(masterFd)

            return PtyResult.Err(PtyError.fromNative(spawn.status, spawn.detail))
        }

        val pid = spawn.fds?.getOrNull(0) ?: -1
        if (pid <= 0) {
            NativePty.closeFd(masterFd)

            return PtyResult.Err(PtyError(PtyError.Kind.INTERNAL, "spawnOnPty returned no pid"))
        }

        val session =
            PtySession(
                id = sessionId,
                masterFd = masterFd,
                slavePath = pair.detail,
                childPid = pid,
                deviceId = deviceId,
                userId = userId,
            )

        val registered = registry.register(session)
        if (registered is PtyResult.Err) {
            // Cap reached or duplicate id: tear the child down rather than leaving
            // an orphaned process with no session pointing at it.
            session.close()
            session.releaseFd()

            return registered
        }

        val channel = allocateChannel(session)
        if (channel < 0) {
            registry.unregister(sessionId)
            session.releaseFd()

            return PtyResult.Err(PtyError(PtyError.Kind.TOO_MANY_SESSIONS, "no free channel"))
        }

        Log.i(TAG, "channel $channel -> session $sessionId (pid $pid, ${pair.detail})")

        return PtyResult.Ok(channel)
    }

    /** Resolves the session behind a channel. */
    fun sessionForChannel(channel: Int): PtySession? = channels[channel]

    /**
     * True when [channel] belongs to [sessionId].
     *
     * This is the device's real resource axis: the device
     * holds one resource — itself — so its binding check is not a scope evaluation
     * but set membership against a fact it minted. A connection may only act on
     * the channels its own session opened.
     */
    fun channelBelongsToSession(
        channel: Int,
        sessionId: String,
    ): Boolean {
        val session = channels[channel] ?: return false

        return session.id == sessionId
    }

    /**
     * Closes a session and frees its channel. The fd is released by the reader,
     * which owns it; this only signals.
     */
    fun close(channel: Int): PtyResult<PtySession> {
        val session =
            channels.remove(channel)
                ?: return PtyResult.Err(
                    PtyError(PtyError.Kind.NO_SUCH_SESSION, "no channel $channel"),
                )

        registry.unregister(session.id)

        return PtyResult.Ok(session)
    }

    val channelCount: Int get() = channels.size

    private fun allocateChannel(session: PtySession): Int {
        for (candidate in 0 until MAX_CHANNELS) {
            if (channels.putIfAbsent(candidate, session) == null) return candidate
        }

        return -1
    }

    private companion object {
        const val TAG = "PtyBroker"
        const val MAX_CHANNELS = 256
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
