package com.vyzorix.service

import android.util.Log
import com.vyzorix.bridge.SessionTokenRegistry
import com.vyzorix.capability.PtyBroker
import com.vyzorix.capability.PtyReader
import com.vyzorix.capability.PtyRequest
import com.vyzorix.capability.PtyResult
import java.util.concurrent.ConcurrentHashMap

/**
 * The service's implementation of [PtySessionPort]: it starts a shell for an
 * operator's terminal, streams its output, and reaps it.
 *
 * It is the cloud-terminal twin of the bridge's session path. Both allocate a PTY
 * from the same [PtyBroker] and read it with the same [PtyReader]; they differ only
 * in where the output goes and where credit comes from — the bridge's client
 * acknowledges bytes, while the cloud socket has no such acknowledgement, so this
 * port replenishes the reader's window as output is handed to the socket and lets a
 * failed socket write close the session. That is the backpressure: OkHttp's send
 * queue is bounded, so a slow operator makes `send` return false and the session
 * ends rather than the device buffering without limit.
 *
 * The shell command is a seam ([shellRequest]) so the terminal can be exercised on
 * an emulator without the Debian rootfs: production passes the proot login shell,
 * a test passes a plain shell.
 *
 * The seam is given the **session id** as well as the token because the shell it
 * builds is named after the session: production wraps the login shell in `tmux
 * new-session -A -s vyzorix-<id>` so a dropped WebSocket reattaches to the same
 * shell (§6.3). A shell request that could not see the session id could not do that.
 */
class ServicePtySessionPort(
    private val broker: PtyBroker,
    private val tokens: SessionTokenRegistry,
    private val deviceId: () -> String,
    private val apiAllowlist: () -> Set<String>,
    private val shellRequest: (sessionId: String, token: String) -> PtyRequest,
    /**
     * Whether the engine can start a shell at all.
     *
     * When the Debian rootfs is not installed, the proot login shell exits the
     * instant it starts — it cannot find its own binary. Opening a PTY for it would
     * give the operator an empty terminal that silently does nothing, which is worse
     * than a refusal: the operator cannot tell a broken engine from a quiet shell.
     * This lets the port refuse with a reason instead.
     */
    private val canOpen: () -> Boolean = { true },
) : PtySessionPort {
    private class Live(
        val channel: Int,
        val reader: PtyReader,
    )

    private val live = ConcurrentHashMap<String, Live>()

    override fun open(
        sessionId: String,
        rows: Int,
        cols: Int,
        onOutput: (sessionId: String, data: ByteArray) -> Unit,
        onExit: (sessionId: String, exitCode: Int) -> Unit,
    ): Boolean {
        // Refuse before allocating a pty when the engine cannot run: a shell that
        // exits instantly would look to the operator like a terminal that opened and
        // then died, with no reason given.
        if (!canOpen()) {
            Log.w(TAG, "terminal $sessionId refused: the engine is not installed")

            return false
        }

        // The token is what lets the shell reach the bridge and be identified. It is
        // scoped to the local API set, stated explicitly: an empty allowlist permits
        // nothing (boundary contract §7).
        val token =
            tokens.issue(
                sessionId = sessionId,
                deviceId = deviceId(),
                operatorId = "",
                apiAllowlist = apiAllowlist(),
            )

        val request = shellRequest(sessionId, token.token).copy(rows = rows, cols = cols)

        val opened = broker.open(sessionId, deviceId(), "", request)
        if (opened is PtyResult.Err) {
            tokens.revokeSession(sessionId)
            Log.w(TAG, "terminal $sessionId could not start: ${opened.error.detail}")

            return false
        }

        val channel = (opened as PtyResult.Ok).value
        val session =
            broker.sessionForChannel(channel) ?: run {
                broker.close(channel)
                tokens.revokeSession(sessionId)

                return false
            }

        // The reader is referenced from inside its own output callback, so it is
        // assigned through a holder rather than captured before it exists.
        val holder = arrayOfNulls<PtyReader>(1)

        val reader =
            PtyReader(
                session = session,
                onOutput = { bytes ->
                    onOutput(sessionId, bytes)
                    // Replenish the window: the cloud socket never acks, so a reader
                    // waiting for credit would stall after the initial window. The
                    // socket's own bounded queue is the real backpressure.
                    holder[0]?.grantCredit(bytes.size)
                },
                onExit = { code ->
                    live.remove(sessionId)
                    tokens.revokeSession(sessionId)
                    onExit(sessionId, code)
                },
            )

        holder[0] = reader
        live[sessionId] = Live(channel, reader)
        reader.start()

        return true
    }

    override fun write(
        sessionId: String,
        data: ByteArray,
    ): Boolean {
        val entry = live[sessionId] ?: return false
        val session = broker.sessionForChannel(entry.channel) ?: return false

        return session.write(data) is PtyResult.Ok
    }

    override fun resize(
        sessionId: String,
        rows: Int,
        cols: Int,
    ): Boolean {
        val entry = live[sessionId] ?: return false
        val session = broker.sessionForChannel(entry.channel) ?: return false

        return session.resize(rows, cols) is PtyResult.Ok
    }

    override fun close(sessionId: String) {
        val entry = live.remove(sessionId) ?: return

        broker.close(entry.channel)
        tokens.revokeSession(sessionId)
        // The reader closes the fd; wait so the caller knows it is gone.
        entry.reader.awaitStopped(CLOSE_WAIT_MS)
    }

    override fun isOpen(sessionId: String): Boolean = live.containsKey(sessionId)

    private companion object {
        const val TAG = "CloudTerminal"
        const val CLOSE_WAIT_MS = 3_000L
    }
}
