package com.vyzorix.connector.terminal

import android.util.Log
import com.vyzorix.connector.cloud.PtyFrame
import com.vyzorix.service.PtySessionPort
import java.util.concurrent.ConcurrentHashMap

/**
 * The device half of the interactive terminal, over the cloud WebSocket.
 *
 * The operator's browser opens a terminal; the server mints a session id and sends
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
 * operator, but it never sends credit back to the device (its own comment says the
 * device's accounting governs input). So the device must not stall waiting for an
 * ACK that never comes: the reader's window is replenished locally as output is
 * handed to the socket, and a session whose socket write fails is closed rather
 * than left blocked.
 */
class TerminalRelay(
    private val port: PtySessionPort,
    private val sendFrame: (ByteArray) -> Boolean,
    private val onSessionClosed: (sessionId: String, reason: String) -> Unit = { _, _ -> },
    private val defaultRows: Int = DEFAULT_ROWS,
    private val defaultCols: Int = DEFAULT_COLS,
) {
    /** Live sessions, keyed by the server's session id. */
    private val sessions = ConcurrentHashMap<String, Session>()

    private class Session(
        val id: String,
        @Volatile var rows: Int,
        @Volatile var cols: Int,
        @Volatile var closing: Boolean = false,
    )

    /** True when [sessionId] is live. */
    fun isOpen(sessionId: String): Boolean = sessions.containsKey(sessionId)

    /** How many sessions are live. Used by the service's diagnostics. */
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

        // A duplicate OPEN for a live session is a retry, not a second shell: opening
        // another would leak a pty and leave the first unreachable.
        if (sessions.containsKey(frame.sessionId)) return true

        // The open payload carries the initial window size (rows, cols). A payload
        // without it falls back to a sane default rather than failing the open: a
        // terminal that is the wrong size is usable, one that never opens is not.
        val (rows, cols) = readResize(frame.payload) ?: (defaultRows to defaultCols)

        val session = Session(frame.sessionId, rows, cols)
        // Register before opening so output that arrives immediately has a home; the
        // port's onOutput can fire from the reader thread as soon as it starts.
        sessions[frame.sessionId] = session

        val opened =
            port.open(
                sessionId = frame.sessionId,
                rows = rows,
                cols = cols,
                onOutput = { id, data -> sendOutput(id, data) },
                onExit = { id, code -> onShellExit(id, code) },
            )

        if (!opened) {
            sessions.remove(frame.sessionId)
            sendError(frame.sessionId, "device could not open a shell")

            return true
        }

        return true
    }

    private fun handleData(frame: PtyFrame.Frame): Boolean {
        val session = sessions[frame.sessionId] ?: return false
        if (session.closing) return true

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

        closeSession(frame.sessionId, "operator_closed")

        return true
    }

    /**
     * Closes a session: the shell is stopped, the session forgotten, and the server
     * told. Idempotent, because an operator close races the shell's own exit.
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
        // released the fd. Tell the server so the operator sees the terminal end
        // rather than hanging on a dead session.
        sendClose(sessionId, exitCode)
        onSessionClosed(sessionId, "shell_exited")
    }

    /**
     * Sends terminal output to the server.
     *
     * A failed send means the socket is gone; the session is closed so the reader
     * stops rather than filling a buffer nobody will drain.
     */
    private fun sendOutput(
        sessionId: String,
        data: ByteArray,
    ) {
        if (data.isEmpty()) return
        if (!sessions.containsKey(sessionId)) return

        val sent = sendFrame(PtyFrame.encodeData(sessionId, data))
        if (!sent) {
            closeSession(sessionId, "socket_write_failed")
        }
    }

    private fun sendClose(
        sessionId: String,
        exitCode: Int,
    ) {
        // The close frame carries the session id and, in the payload, the exit code
        // as a decimal string so the operator can show it. The server treats the
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

    companion object {
        /** A conventional terminal size, used when the open frame carries none. */
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80
    }
}
