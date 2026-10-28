package com.vyzorix.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON codec for control frames. `ignoreUnknownKeys` so a newer client can add fields. */
internal val BridgeJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/** A request. [id] correlates the response; [api] selects the handler. */
@Serializable
data class Request(
    val id: String,
    val api: String,
    val args: Map<String, String> = emptyMap(),
)

/** A response. Exactly one of [result] and [error] is set. */
@Serializable
data class Response(
    val id: String,
    val ok: Boolean,
    val result: String? = null,
    val error: BridgeError? = null,
)

/** A failure, in the shape the server's error model uses. */
@Serializable
data class BridgeError(
    val kind: String,
    val detail: String,
)

/** Flow-control acknowledgement for a PTY channel. */
@Serializable
data class PtyCredit(
    @SerialName("channel") val channel: Int,
    val bytes: Int,
)

/** An unsolicited event pushed to the client. */
@Serializable
data class Event(
    val topic: String,
    val data: String,
)

/**
 * The body of a successful `auth` response. A typed shape rather than a
 * hand-built JSON string, so the session id cannot be injected into the JSON.
 */
@Serializable
data class AuthResult(
    val session: String,
)

/** Well-known error kinds, mirroring the native status vocabulary. */
object BridgeErrorKind {
    const val FORBIDDEN = "forbidden"
    const val UNKNOWN_API = "unknown_api"
    const val INVALID_ARGS = "invalid_args"
    const val NO_SUCH_SESSION = "no_such_session"
    const val INTERNAL = "internal"
}

/** The API name used to present a token. Served before any other. */
const val API_AUTH_NAME = "auth"

/** Argument name carrying the session token on the auth request. */
const val ARG_TOKEN_NAME = "token"

/** PTY channel frame payloads are `[1B channel][bytes]`. */
object PtyFrame {
    private const val CHANNEL_PREFIX_BYTES = 1

    /** Mask for one unsigned byte, so a channel above 127 stays positive. */
    private const val BYTE_MASK = 0xFF

    fun encode(
        channel: Int,
        bytes: ByteArray,
    ): ByteArray {
        val out = ByteArray(bytes.size + CHANNEL_PREFIX_BYTES)
        out[0] = channel.toByte()
        bytes.copyInto(out, CHANNEL_PREFIX_BYTES)

        return out
    }

    /** Returns null when the payload is too short to carry a channel byte. */
    fun decode(payload: ByteArray): Pair<Int, ByteArray>? {
        if (payload.isEmpty()) return null

        val channel = payload[0].toInt() and BYTE_MASK
        val body = payload.copyOfRange(CHANNEL_PREFIX_BYTES, payload.size)

        return channel to body
    }
}
