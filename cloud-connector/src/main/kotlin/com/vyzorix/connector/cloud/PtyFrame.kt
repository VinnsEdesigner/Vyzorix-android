package com.vyzorix.connector.cloud

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The binary PTY frame format, mirrored from the server's `internal/ws/pty_frame.go`.
 *
 * Terminal traffic is binary, not JSON: base64 and escaping on every keystroke and
 * every byte of output would be wasteful, and a `cat` of a binary file would not
 * survive a JSON string.
 *
 * Wire format, big-endian:
 *
 *     [1B channel][1B type][2B length][payload...]
 *
 * The 2-byte length is the bound on a single frame: a peer cannot make the other
 * side allocate an unbounded buffer by claiming a huge frame. The same constant is
 * enforced on both sides.
 *
 * A DATA payload is session-prefixed because one socket multiplexes several
 * sessions:
 *
 *     [1B idLen][idLen bytes session id][data...]
 */
object PtyFrame {
    const val HEADER_SIZE = 4

    /** The largest payload one frame may carry. Matches the server's `MaxPTYPayload`. */
    const val MAX_PAYLOAD = 64 * 1024

    /** The only channel defined today. */
    const val CHANNEL_PTY: Byte = 0x01

    const val TYPE_DATA: Byte = 0x01
    const val TYPE_ACK: Byte = 0x02
    const val TYPE_OPEN: Byte = 0x03
    const val TYPE_CLOSE: Byte = 0x04
    const val TYPE_RESIZE: Byte = 0x05
    const val TYPE_ERROR: Byte = 0x06

    /** Bounds the session id field. A UUID is 36 bytes. */
    const val MAX_SESSION_ID_LEN = 64

    /** The resize payload is two uint16s: rows then cols. */
    const val RESIZE_PAYLOAD_SIZE = 4

    /** The ACK payload is one int32 credit count. */
    const val ACK_PAYLOAD_SIZE = 4

    /** The mask for an unsigned 16-bit read. */
    private const val UINT16_MASK = 0xFFFF

    /** The mask for an unsigned byte read. */
    private const val BYTE_MASK = 0xFF

    /**
     * One decoded frame.
     *
     * [sessionId] is set for DATA, OPEN and RESIZE frames (the ones that name a
     * session); it is empty for CLOSE and ACK, which the server sends without a
     * session header on close.
     */
    data class Frame(
        val channel: Byte,
        val type: Byte,
        val sessionId: String,
        val payload: ByteArray,
    ) {
        // ByteArray in a data class: equals/hashCode must be by content, or two frames
        // with identical payloads compare unequal. Used by tests.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false

            return channel == other.channel &&
                type == other.type &&
                sessionId == other.sessionId &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = channel.toInt()
            result = 31 * result + type
            result = 31 * result + sessionId.hashCode()
            result = 31 * result + payload.contentHashCode()

            return result
        }
    }

    /** Raised on a malformed frame. Never fatal to the connection: the frame is dropped. */
    class MalformedFrame(
        message: String,
    ) : Exception(message)

    /** Builds a frame with a raw payload and no session header. */
    fun encode(
        type: Byte,
        payload: ByteArray,
        sessionId: String = "",
    ): ByteArray {
        val body = if (sessionId.isEmpty()) payload else sessionData(sessionId, payload)
        require(body.size <= MAX_PAYLOAD) { "pty payload ${body.size} exceeds max $MAX_PAYLOAD" }

        val out = ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.BIG_ENDIAN)
        out.put(CHANNEL_PTY)
        out.put(type)
        out.putShort(body.size.toShort())
        out.put(body)

        return out.array()
    }

    /** A DATA frame carrying terminal output, session-prefixed. */
    fun encodeData(
        sessionId: String,
        data: ByteArray,
    ): ByteArray = encode(TYPE_DATA, data, sessionId)

    /** A resize frame: session id, then rows and cols as big-endian uint16. */
    fun encodeResize(
        sessionId: String,
        rows: Int,
        cols: Int,
    ): ByteArray = encode(TYPE_RESIZE, resizePayload(rows, cols), sessionId)

    /**
     * The bare 4-byte resize payload, rows then cols as big-endian uint16.
     *
     * Used on its own for an OPEN frame, whose payload is the initial window size
     * with no inner frame around it: the open frame is already the frame.
     */
    fun resizePayload(
        rows: Int,
        cols: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(RESIZE_PAYLOAD_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(rows.toShort())
            .putShort(cols.toShort())
            .array()

    /** An ACK frame returning flow-control credit. */
    fun encodeAck(count: Int): ByteArray {
        val payload =
            ByteBuffer
                .allocate(ACK_PAYLOAD_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(count)
                .array()

        return encode(TYPE_ACK, payload)
    }

    /**
     * Decodes one frame. Throws [MalformedFrame] on a bad header or an over-large
     * declared length, so the caller drops the frame rather than the connection.
     */
    fun decode(raw: ByteArray): Frame {
        if (raw.size < HEADER_SIZE) throw MalformedFrame("frame shorter than header")

        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val channel = buffer.get()
        val type = buffer.get()
        val length = buffer.short.toInt() and UINT16_MASK

        if (length >
            MAX_PAYLOAD
        ) {
            throw MalformedFrame("frame declares $length bytes, max is $MAX_PAYLOAD")
        }
        if (raw.size < HEADER_SIZE + length) {
            throw MalformedFrame(
                "frame declares $length payload bytes but carries ${raw.size - HEADER_SIZE}",
            )
        }

        val body = raw.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)

        // The frames that name a session carry the session header. The server's
        // pty_device.go reads CLOSE with DecodeSessionData and treats ERROR as a raw
        // string, so CLOSE is session-prefixed and ERROR is not — the decoder has to
        // match, or a close arrives with an empty id and is unroutable.
        val (sessionId, payload) =
            when (type) {
                TYPE_DATA, TYPE_OPEN, TYPE_RESIZE, TYPE_CLOSE -> {
                    val decoded = decodeSessionData(body)
                    decoded.first to decoded.second
                }
                else -> "" to body
            }

        return Frame(channel, type, sessionId, payload)
    }

    /** Reads rows/cols from a resize frame's payload. */
    fun decodeResize(payload: ByteArray): Pair<Int, Int> {
        if (payload.size < RESIZE_PAYLOAD_SIZE) {
            throw MalformedFrame("resize payload shorter than $RESIZE_PAYLOAD_SIZE bytes")
        }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        return (buffer.short.toInt() and UINT16_MASK) to (buffer.short.toInt() and UINT16_MASK)
    }

    private fun sessionData(
        sessionId: String,
        data: ByteArray,
    ): ByteArray {
        require(sessionId.toByteArray(Charsets.UTF_8).size <= MAX_SESSION_ID_LEN) {
            "session id exceeds $MAX_SESSION_ID_LEN bytes"
        }
        val idBytes = sessionId.toByteArray(Charsets.UTF_8)
        val out = ByteBuffer.allocate(1 + idBytes.size + data.size)
        out.put(idBytes.size.toByte())
        out.put(idBytes)
        out.put(data)

        return out.array()
    }

    private fun decodeSessionData(payload: ByteArray): Pair<String, ByteArray> {
        if (payload.isEmpty()) throw MalformedFrame("session-prefixed payload is empty")

        val idLen = payload[0].toInt() and BYTE_MASK
        if (idLen == 0 ||
            idLen > MAX_SESSION_ID_LEN
        ) {
            throw MalformedFrame("invalid session id length $idLen")
        }
        if (payload.size < 1 + idLen) throw MalformedFrame("session id truncated")

        val sessionId = String(payload, 1, idLen, Charsets.UTF_8)
        val data = payload.copyOfRange(1 + idLen, payload.size)

        return sessionId to data
    }
}
