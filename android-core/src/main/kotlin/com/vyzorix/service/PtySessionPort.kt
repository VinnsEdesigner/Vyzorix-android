package com.vyzorix.service

/**
 * The PTY side of a terminal session, as the cloud relay needs it.
 *
 * The relay speaks the wire protocol (open, resize, close, data) and nothing else;
 * this is what it calls to actually start, size, write to and stop a shell on the
 * device. The service implements it because the service owns the PTY broker, the
 * session tokens and the readers — the cloud connector must not reach into any of
 * that, and `android-core` must not depend on the connector. The interface is the
 * whole seam.
 *
 * Sessions are keyed by the **server's** session id: the user opened the
 * terminal, the server minted the id, and both sides route frames by it. The
 * device's own channel number is an implementation detail behind this interface.
 */
interface PtySessionPort {
    /**
     * Opens a shell for [sessionId] and starts streaming its output.
     *
     * [identity] is the user the server resolved for this session — who they are
     * and the API allowlist they were granted. It is delivered to the container's
     * shell (as `VYZORIX_SESSION`) so the Vyzorix CLI inside it can show the user
     * and offer only what they may call. Null means the server sent no identity (an
     * older server, or a reattach), which is "unknown user", not a refusal.
     *
     * [onOutput] is called with terminal output as it arrives, from the reader
     * thread. It must be safe to call from any thread, and the implementation must
     * not block the reader: a slow consumer drops output rather than stalling the
     * shell (flow control is the relay's concern, not the reader's).
     *
     * [onExit] is called once when the shell's process ends, with its exit code.
     *
     * Returns true when the session opened. A false return means the session could
     * not start (the container is missing, the session cap is reached) and the relay
     * reports the failure to the server.
     */
    fun open(
        sessionId: String,
        rows: Int,
        cols: Int,
        identity: SessionIdentity?,
        onOutput: (sessionId: String, data: ByteArray) -> Unit,
        onExit: (sessionId: String, exitCode: Int) -> Unit,
    ): Boolean

    /** Writes user keystrokes to the session's shell. */
    fun write(
        sessionId: String,
        data: ByteArray,
    ): Boolean

    /** Applies a new window size to the session's pty. */
    fun resize(
        sessionId: String,
        rows: Int,
        cols: Int,
    ): Boolean

    /**
     * Closes the session and reaps its process. Idempotent: closing a session that
     * already exited (or never opened) is not an error, because the user's close
     * can race the shell's own exit.
     */
    fun close(sessionId: String)

    /** True when the session is live, so the relay can skip work for a dead one. */
    fun isOpen(sessionId: String): Boolean
}
