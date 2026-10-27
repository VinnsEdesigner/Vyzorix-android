package com.vyzorix.capability

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks live sessions.
 *
 * Session ids come from the client, so [register] rejects a duplicate rather than
 * replacing: overwriting would leak the first session (never closed, still
 * counted against the cap) and let one operator adopt another's session by
 * guessing its id.
 *
 * Not durable by design — a session is a live connection, meaningless after a
 * restart.
 */
class PtySessionRegistry(
    private val maxSessionsPerDevice: Int = DEFAULT_MAX_SESSIONS_PER_DEVICE,
) {
    private val sessions = ConcurrentHashMap<String, PtySession>()
    private val perDevice = ConcurrentHashMap<String, Int>()

    /** Registers a session. Fails if the id is taken or the device is at its cap. */
    fun register(session: PtySession): PtyResult<Unit> {
        val deviceCount = perDevice.getOrDefault(session.deviceId, 0)
        if (deviceCount >= maxSessionsPerDevice) {
            return PtyResult.Err(
                PtyError(
                    PtyError.Kind.TOO_MANY_SESSIONS,
                    "device ${session.deviceId} already holds $deviceCount sessions " +
                        "(cap $maxSessionsPerDevice)",
                ),
            )
        }

        if (sessions.putIfAbsent(session.id, session) != null) {
            return PtyResult.Err(
                PtyError(PtyError.Kind.SESSION_EXISTS, "session id ${session.id} is already in use"),
            )
        }

        perDevice.compute(session.deviceId) { _, count -> (count ?: 0) + 1 }

        return PtyResult.Ok(Unit)
    }

    fun get(id: String): PtySession? = sessions[id]

    /** Closes a session and removes it. The reader still owns the fd. */
    fun unregister(id: String): PtyResult<PtySession> {
        val session = sessions.remove(id)
            ?: return PtyResult.Err(
                PtyError(PtyError.Kind.NO_SUCH_SESSION, "no session $id"),
            )

        perDevice.computeIfPresent(session.deviceId) { _, count -> if (count <= 1) null else count - 1 }

        session.close()

        return PtyResult.Ok(session)
    }

    /**
     * Closes every session for a device and returns them. Used when the device
     * disconnects: a session cannot outlive the connection it runs over.
     */
    fun unregisterAllForDevice(deviceId: String): List<PtySession> {
        val owned = sessions.values.filter { it.deviceId == deviceId }
        owned.forEach { session ->
            sessions.remove(session.id)
            session.close()
        }
        perDevice.remove(deviceId)

        return owned
    }

    /** Sessions idle for longer than [idleMs]. */
    fun idleSessions(idleMs: Long): List<PtySession> =
        sessions.values.filter { it.idleForMs > idleMs }

    val count: Int get() = sessions.size

    fun countForDevice(deviceId: String): Int = perDevice.getOrDefault(deviceId, 0)

    companion object {
        const val DEFAULT_MAX_SESSIONS_PER_DEVICE = 4
    }
}
