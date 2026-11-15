package com.vyzorix.connector.terminal

import android.util.Log
import com.vyzorix.connector.cloud.PtyFrame
import com.vyzorix.service.PtySessionPort
import com.vyzorix.service.SessionIdentity
import java.util.concurrent.ConcurrentHashMap

/**
 * The device half of the interactive terminal, over the cloud WebSocket.
 *
 * The user's browser opens a terminal; the server mints a session id and sends
 * the device a binary OPEN frame. From then on the two sides exchange DATA frames
 * (keystrokes down, output up), RESIZE frames and a CLOSE frame, all multiplexed
 * over the one device socket and all keyed by that session id.
 *
 * This class is that relay: it decodes the frames, drives the PTY sessions through
 * [PtySessionPort], and encodes the device's own frames back. It owns no socket and
 * no PTY — the socket is the caller's and the shell is the port's — so it is pure
 * routing and can be tested without either.
 *
 * Flow control: the server applies a credit window to output going *to* the
 * user, but it never sends credit back to the device (its own comment says the
 * device's accounting governs input). So the device must not stall waiting for an
 * ACK that never comes: the reader's window is replenished locally as output is
 * handed to the socket, and a session whose socket write fails is closed rather
 * than left blocked.
 *
 * ## Detach and reattach (a shell outlives the socket)
 *
 * A session survives the WebSocket. When the socket drops the relay **detaches**
 * every live session instead of closing it: the shell keeps running, its output is
 * buffered up to a bound, and a reconnect re-attaches by the same session id — the
 * browser sends `pty_open` with the id it used before, the server re-opens it, and
 * the device re-binds the surviving shell rather than spawning a second one.
 *
 * This is the device-side persistence model (no multiplexer): the app already owns
 * the PTY master fd, so the shell's lifetime is the *session's*, not the socket's.
 * The buffer is bounded because an unbounded one would grow with whatever the shell
 * prints while nobody is watching; when it overflows, the **oldest** bytes are
 * dropped — a terminal is a tail, and the most recent output is what a user
 * needs. A detached session is reaped after [detachedTtlMs] so a closed tab cannot
 * hold a shell forever.
 */
class TerminalRelay(
    private val port: PtySessionPort,
    private val sendFrame: (ByteArray) -> Boolean,
    private val onSessionClosed: (sessionId: String, reason: String) -> Unit = { _, _ -> },
    private val defaultRows: Int = DEFAULT_ROWS,
    private val defaultCols: Int = DEFAULT_COLS,
    /**
     * How long a detached session is kept before it is reaped.
     *
     * The server's idle TTL is the backstop for a session the user forgets; this
     * is the device's own bound for a session whose socket has gone and may never
     * return. It is deliberately shorter: a device is a phone, and a shell holding
     * memory for a client that is not coming back is worth reclaiming sooner.
     */
    private val detachedTtlMs: Long = DETACHED_TTL_MS,
    /** Milliseconds since epoch; injectable so the TTL is testable without sleeping. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Buffered output kept per detached session; the oldest bytes are dropped first. */
    private val detachedBufferBytes: Int = DETACHED_BUFFER_BYTES,
) {
    /** Live sessions, keyed by the server's session id. */
    private val sessions = ConcurrentHashMap<String, Session>()

    private class Session(
        val id: String,
        @Volatile var rows: Int,
        @Volatile var cols: Int,
        @Volatile var closing: Boolean = false,
        /**
         * True while no socket is attached. The shell is running and its output is
         * being buffered rather than sent.
         */
        @Volatile var detached: Boolean = false,
        /** When the session was detached, for the reap TTL. Zero while attached. */
        @Volatile var detachedAt: Long = 0,
        /** Output produced while detached, oldest first. Guarded by its own lock. */
        val pending: ArrayDeque<ByteArray> = ArrayDeque(),
        @Volatile var pendingBytes: Int = 0,
    )

    /** True when [sessionId] is live, attached or detached. */
    fun isOpen(sessionId: String): Boolean = sessions.containsKey(sessionId)

    /** How many sessions are live, attached or detached. Used by the service's diagnostics. */
    fun openCount(): Int = sessions.size

    /**
     * Handles one binary frame from the server.
     *
     * Returns true when the frame was a well-formed PTY frame this relay acted on
     * (or deliberately ignored, such as an ACK). A malformed frame returns false so
     * the caller can log it; it never throws, because a bad frame must not take the
     * socket down.
     */
    @Suppress("SwallowedException") // A malformed frame is dropped; false is the whole answer.
    fun onFrame(raw: ByteArray): Boolean {
        val frame =
            try {
                PtyFrame.decode(raw)
            } catch (e: PtyFrame.MalformedFrame) {
                return false
            }

        if (frame.channel != PtyFrame.CHANNEL_PTY) return false

        return when (frame.type) {
            PtyFrame.TYPE_OPEN -> handleOpen(frame)
            PtyFrame.TYPE_DATA -> handleData(frame)
            PtyFrame.TYPE_RESIZE -> handleResize(frame)
            PtyFrame.TYPE_CLOSE -> handleClose(frame)
            PtyFrame.TYPE_ACK -> true // The server does not credit us; nothing to do.
            else -> false
        }
    }

    private fun handleOpen(frame: PtyFrame.Frame): Boolean {
        if (frame.sessionId.isEmpty()) return false

        // The open payload may carry the window size (rows, cols). A payload without
        // it is not an error: a browser open falls back to a sane default, and a
        // *server-initiated* reattach carries none precisely so the shell keeps the
        // size it already had (see the reattach branch below).
        val (size, identity) = readOpen(frame.payload)

        // An OPEN for a session we already hold is a **reattach**, not a second shell.
        //
        // The browser reuses the session id it used before, so a reconnect re-opens
        // the same id; the server re-opens it (its registry was rebuilt when the
        // socket dropped) and sends OPEN again. The shell for that id is still running
        // here — detach kept it — so the correct answer is to re-bind it: flush what
        // it produced while detached and mark it attached. Spawning a second shell
        // would leak the first, which is exactly the bug the old code avoided by
        // treating a duplicate OPEN as a no-op; now it does something useful instead.
        val existing = sessions[frame.sessionId]
        if (existing != null) {
            if (!existing.closing) {
                reattach(existing, size)
            }

            return true
        }

        val (rows, cols) = size ?: (defaultRows to defaultCols)
        val session = Session(frame.sessionId, rows, cols)
        // Register before opening so output that arrives immediately has a home; the
        // port's onOutput can fire from the reader thread as soon as it starts.
        sessions[frame.sessionId] = session

        val opened =
            port.open(
                sessionId = frame.sessionId,
                rows = rows,
                cols = cols,
                identity = identity,
                onOutput = { id, data -> onSessionOutput(id, data) },
                onExit = { id, code -> onShellExit(id, code) },
            )

        if (!opened) {
            sessions.remove(frame.sessionId)
            sendError(frame.sessionId, "device could not open a shell")

            return true
        }

        return true
    }

    /**
     * Re-binds an OPEN to a shell we already hold, after a reconnect.
     *
     * Applies [size] only when the reattach carried one — a server-initiated
     * reattach sends none, and the shell should keep the size it already had rather
     * than shrink to the default — then marks the session attached and flushes the
     * output buffered while it was detached. The flush is in order and before any new
     * output, because the reader appends to the buffer under the same lock this
     * drains it under.
     */
    private fun reattach(
        session: Session,
        size: Pair<Int, Int>?,
    ) {
        if (size != null) {
            session.rows = size.first
            session.cols = size.second
            port.resize(session.id, size.first, size.second)
        }

        val buffered =
            synchronized(session.pending) {
                session.detached = false
                session.detachedAt = 0
                val flushed = session.pending.toList()
                session.pending.clear()
                session.pendingBytes = 0

                flushed
            }

        buffered.forEach { data ->
            sendFrame(PtyFrame.encodeData(session.id, data))
        }
    }

    private fun handleData(frame: PtyFrame.Frame): Boolean {
        val session = sessions[frame.sessionId] ?: return false
        if (session.closing) return true

        // Input while detached is dropped. It cannot have come from the user (the
        // socket is down), and writing it to the shell would inject keystrokes nobody
        // sent. A reattach is a fresh view of the same shell, not a replay of input.
        if (session.detached) return true

        if (frame.payload.isNotEmpty()) {
            port.write(frame.sessionId, frame.payload)
        }

        return true
    }

    private fun handleResize(frame: PtyFrame.Frame): Boolean {
        val session = sessions[frame.sessionId] ?: return false
        val (rows, cols) = readResize(frame.payload) ?: return false

        session.rows = rows
        session.cols = cols
        port.resize(frame.sessionId, rows, cols)

        return true
    }

    private fun handleClose(frame: PtyFrame.Frame): Boolean {
        // The server names the session it is closing. A close with no session id is
        // ambiguous when several terminals are open, so it is refused rather than
        // guessed at — closing the wrong shell is worse than leaving one open.
        if (frame.sessionId.isEmpty()) return false

        closeSession(frame.sessionId, "user_closed")

        return true
    }

    /**
     * Closes a session: the shell is stopped, the session forgotten, and the server
     * told. Idempotent, because a user close races the shell's own exit.
     */
    fun closeSession(
        sessionId: String,
        reason: String,
    ) {
        val session = sessions.remove(sessionId) ?: return
        session.closing = true

        port.close(sessionId)
        onSessionClosed(sessionId, reason)
    }

    /** Closes every session. Called when the device socket drops: a shell cannot outlive it. */
    fun closeAll(reason: String) {
        for (sessionId in sessions.keys.toList()) {
            closeSession(sessionId, reason)
        }
    }

    /** The shell's process ended on its own; tell the server and forget the session. */
    private fun onShellExit(
        sessionId: String,
        exitCode: Int,
    ) {
        val session = sessions.remove(sessionId) ?: return
        session.closing = true

        // The shell is already gone, so the port needs no close; the reader has
        // released the fd. Tell the server so the user sees the terminal end
        // rather than hanging on a dead session.
        sendClose(sessionId, exitCode)
        onSessionClosed(sessionId, "shell_exited")
    }

    /**
     * Terminal output from the reader: send it, or buffer it while detached.
     *
     * Attached, a failed send means the socket is gone. That no longer closes the
     * session — it **detaches** it, so the shell survives the drop and a reconnect
     * re-binds it (see [detachAll]). Detached, output is appended to the bounded
     * buffer and the oldest bytes are dropped on overflow: a terminal is a tail, and
     * a detached shell printing without limit must not grow the device's memory.
     */
    private fun onSessionOutput(
        sessionId: String,
        data: ByteArray,
    ) {
        if (data.isEmpty()) return

        val session = sessions[sessionId] ?: return

        if (session.detached) {
            buffer(session, data)

            return
        }

        if (!sendFrame(PtyFrame.encodeData(sessionId, data))) {
            // The write failed: the socket is gone but the shell is not. Detach this
            // session so its output is kept for the reconnect rather than lost or
            // treated as a dead shell.
            detach(session)
        }
    }

    /** Appends output to a detached session's buffer, dropping the oldest on overflow. */
    private fun buffer(
        session: Session,
        data: ByteArray,
    ) {
        synchronized(session.pending) {
            // Re-check under the lock: a reattach between the caller's check and here
            // would have drained the buffer and marked the session attached, and
            // buffering then would strand the bytes until the next detach.
            if (!session.detached) {
                sendFrame(PtyFrame.encodeData(session.id, data))

                return
            }

            session.pending.addLast(data)
            session.pendingBytes += data.size

            while (session.pendingBytes > detachedBufferBytes && session.pending.isNotEmpty()) {
                val dropped = session.pending.removeFirst()
                session.pendingBytes -= dropped.size
            }
        }
    }

    /** Marks a session detached so its shell keeps running with no socket attached. */
    private fun detach(session: Session) {
        if (session.detached) return

        session.detached = true
        session.detachedAt = now()
    }

    /**
     * Detaches every live session, keeping each shell running.
     *
     * This replaces the old behaviour of closing every session on a dropped socket.
     * A session whose socket write already failed has detached itself; this covers
     * the ones that had not written since the drop. Called by the connection on
     * disconnect, and before [closeAll] on an explicit stop, so a service shutdown
     * still reaps every shell.
     */
    fun detachAll() {
        for (session in sessions.values) {
            if (!session.closing) detach(session)
        }
    }

    /**
     * Reaps detached sessions older than the TTL, and returns how many were closed.
     *
     * Called on connect and by the connection's periodic tick. The server's idle TTL
     * is the backstop for a forgotten tab that is still *connected*; this is the
     * device's bound for a shell whose socket has gone and may never return.
     */
    fun reapDetached(): Int {
        val cutoff = now() - detachedTtlMs
        var reaped = 0

        for (session in sessions.values.toList()) {
            if (session.detached && session.detachedAt in 1..cutoff) {
                closeSession(session.id, "detached_timeout")
                reaped++
            }
        }

        return reaped
    }

    private fun sendClose(
        sessionId: String,
        exitCode: Int,
    ) {
        // The close frame carries the session id and, in the payload, the exit code
        // as a decimal string so the user can show it. The server treats the
        // payload as opaque for close, so a text code is safe and useful.
        sendFrame(
            PtyFrame.encode(PtyFrame.TYPE_CLOSE, exitCode.toString().toByteArray(), sessionId),
        )
    }

    private fun sendError(
        sessionId: String,
        message: String,
    ) {
        // The server reads an ERROR frame's payload as a raw detail string
        // (`string(payload)` in pty_device.go), so this frame is deliberately NOT
        // session-prefixed: prefixing it would make the server log the session id as
        // part of the error text. The session id is logged on this side instead.
        Log.w("TerminalRelay", "session $sessionId: $message")
        sendFrame(PtyFrame.encode(PtyFrame.TYPE_ERROR, message.toByteArray()))
    }

    /** Reads a (rows, cols) pair from a resize-shaped payload, or null when absent. */
    @Suppress("SwallowedException") // A short payload means "no size given"; null is the answer.
    private fun readResize(payload: ByteArray): Pair<Int, Int>? {
        if (payload.size < PtyFrame.RESIZE_PAYLOAD_SIZE) return null

        return try {
            PtyFrame.decodeResize(payload)
        } catch (e: PtyFrame.MalformedFrame) {
            null
        }
    }

    /**
     * Reads an OPEN payload: the window size and, when the server sent one, the
     * user identity.
     *
     * The size is read on its own first so an older server's payload (size only) still
     * yields a size; the identity is null in that case, which the port treats as an
     * unattributed session rather than a failure.
     */
    private fun readOpen(payload: ByteArray): Pair<Pair<Int, Int>?, SessionIdentity?> {
        if (payload.size < PtyFrame.RESIZE_PAYLOAD_SIZE) return null to null

        return try {
            PtyFrame.decodeOpen(payload)
        } catch (expected: PtyFrame.MalformedFrame) {
            // A malformed OPEN is not fatal to the session: the shell opens with a
            // default size and an unknown user.
            null to null
        }
    }

    companion object {
        /** A conventional terminal size, used when the open frame carries none. */
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80

        /**
         * How long a detached session is kept before it is reaped.
         *
         * Ten minutes: long enough to survive a reconnect after a brief network blip,
         * short enough that a shell left by a closed tab is reclaimed without waiting
         * for the server's 2 h idle TTL. The server TTL still governs a session whose
         * socket is up but idle.
         */
        const val DETACHED_TTL_MS = 10 * 60 * 1000L

        /**
         * Output buffered per detached session, in bytes.
         *
         * 256 KiB holds a screenfuls of scrollback — the tail a user reconnecting
         * wants to see — without letting a detached `yes` grow the device's memory.
         * The oldest bytes are dropped past this bound.
         */
        const val DETACHED_BUFFER_BYTES = 256 * 1024
    }
}
