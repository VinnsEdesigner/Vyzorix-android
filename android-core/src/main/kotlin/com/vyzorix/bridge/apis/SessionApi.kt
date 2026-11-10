package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.capability.PtyError
import com.vyzorix.capability.PtyResult
import org.json.JSONObject

/**
 * Opens and closes PTY sessions.
 *
 * This is the door the terminal comes through: `session.open` allocates a PTY,
 * launches the container (proot + a login shell) on it, starts the reader, and
 * returns the channel the client uses on subsequent `pty_input` / `pty_output`
 * frames. `session.close` tears the session down.
 *
 * The session is bound to the caller's token: the device and operator come from
 * the validated token, never from client-supplied arguments, so a client cannot
 * open a session attributed to someone else.
 *
 * A handler is a plain function here rather than an object because it needs the
 * service's session table, which only the service holds. The service supplies the
 * two callbacks; this file is the argument parsing and the result shaping.
 */
class SessionApi(
    private val openSession: (
        sessionId: String,
        deviceId: String,
        operatorId: String,
    ) -> PtyResult<Int>,
    private val closeSession: (channel: Int) -> PtyResult<Unit>,
    private val resizeSession: (channel: Int, rows: Int, cols: Int) -> PtyResult<Unit>,
    /**
     * True when [channel] belongs to [sessionId]. This is the device's binding
     * check: a connection may only act on the channels its
     * own session opened, so one operator cannot close or resize another's
     * terminal.
     */
    private val channelOwnedBy: (channel: Int, sessionId: String) -> Boolean,
) : ApiHandler {

    override fun handle(call: ApiCall): ApiOutcome {
        val action = call.args["action"] ?: OPEN

        return when (action) {
            OPEN -> open(call)
            CLOSE -> close(call)
            RESIZE -> resize(call)
            else -> ApiOutcome.fail(BridgeErrorKind.INVALID_ARGS, "unknown action $action")
        }
    }

    private fun open(call: ApiCall): ApiOutcome {
        val token = call.token

        // The session id is client-chosen so a client can correlate its own
        // shells, but the device and operator are taken from the token so the
        // session is attributed to the authenticated caller.
        val sessionId = call.args["sessionId"] ?: "session-${System.nanoTime()}"

        val opened =
            openSession(
                sessionId,
                token.deviceId,
                token.operatorId,
            )

        return when (opened) {
            is PtyResult.Ok ->
                ApiOutcome.ok(
                    JSONObject()
                        .put("channel", opened.value)
                        .put("session", sessionId),
                )
            is PtyResult.Err -> ApiOutcome.fail(mapKind(opened.error), opened.error.detail)
        }
    }

    private fun close(call: ApiCall): ApiOutcome {
        val channel =
            call.args["channel"]?.toIntOrNull()
                ?: return ApiOutcome.fail(BridgeErrorKind.INVALID_ARGS, "channel is required")

        if (!call.token.permitsChannel(channel, channelOwnedBy)) {
            return ApiOutcome.fail(
                BridgeErrorKind.FORBIDDEN,
                "channel $channel is not owned by session ${call.token.sessionId}",
            )
        }

        return when (val closed = closeSession(channel)) {
            is PtyResult.Ok -> ApiOutcome.ok(JSONObject().put("closed", channel))
            is PtyResult.Err -> ApiOutcome.fail(mapKind(closed.error), closed.error.detail)
        }
    }

    private fun resize(call: ApiCall): ApiOutcome {
        val channel =
            call.args["channel"]?.toIntOrNull()
                ?: return ApiOutcome.fail(BridgeErrorKind.INVALID_ARGS, "channel is required")
        val rows =
            call.args["rows"]?.toIntOrNull()
                ?: return ApiOutcome.fail(BridgeErrorKind.INVALID_ARGS, "rows is required")
        val cols =
            call.args["cols"]?.toIntOrNull()
                ?: return ApiOutcome.fail(BridgeErrorKind.INVALID_ARGS, "cols is required")

        if (!call.token.permitsChannel(channel, channelOwnedBy)) {
            return ApiOutcome.fail(
                BridgeErrorKind.FORBIDDEN,
                "channel $channel is not owned by session ${call.token.sessionId}",
            )
        }

        return when (val resized = resizeSession(channel, rows, cols)) {
            is PtyResult.Ok -> ApiOutcome.ok(JSONObject().put("channel", channel))
            is PtyResult.Err -> ApiOutcome.fail(mapKind(resized.error), resized.error.detail)
        }
    }

    private fun mapKind(error: PtyError): String =
        when (error.kind) {
            PtyError.Kind.NO_SUCH_SESSION -> BridgeErrorKind.NO_SUCH_SESSION
            PtyError.Kind.INVALID_ARGUMENT -> BridgeErrorKind.INVALID_ARGS
            else -> BridgeErrorKind.INTERNAL
        }

    companion object {
        const val NAME = "session"
        const val OPEN = "open"
        const val CLOSE = "close"
        const val RESIZE = "resize"
    }
}
