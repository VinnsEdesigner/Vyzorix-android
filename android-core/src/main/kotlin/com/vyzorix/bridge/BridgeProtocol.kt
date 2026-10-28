package com.vyzorix.bridge

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Wire codes for the frame types below.
 *
 * File-private constants rather than enum properties: an enum entry cannot read
 * its own companion's constants, because the companion is not initialized yet.
 */
private const val CODE_REQUEST: Byte = 0x01
private const val CODE_RESPONSE: Byte = 0x02
private const val CODE_PTY_OUTPUT: Byte = 0x03
private const val CODE_PTY_INPUT: Byte = 0x04
private const val CODE_PTY_CREDIT: Byte = 0x05
private const val CODE_EVENT: Byte = 0x06

enum class FrameType(
    val code: Byte,
) {
    Request(CODE_REQUEST),
    Response(CODE_RESPONSE),
    PtyOutput(CODE_PTY_OUTPUT),
    PtyInput(CODE_PTY_INPUT),
    PtyCredit(CODE_PTY_CREDIT),
    Event(CODE_EVENT),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Byte): FrameType? = byCode[code]
    }
}

/** A decoded frame. [payload] is a copy; the caller owns it. */
class Frame(
    val type: FrameType,
    val payload: ByteArray,
)

/**
 * Length-prefixed binary framing.
 *
 * ```
 * [4B length][1B type][payload]
 * ```
 *
 * The length is the payload length, big-endian. A frame longer than [MAX_PAYLOAD]
 * is rejected rather than allocated: without a bound a client can make the server
 * allocate without limit.
 */
object BridgeProtocol {

    /** Largest payload a single frame may carry. */
    const val MAX_PAYLOAD = 1 shl 20

    private const val HEADER_SIZE = 5
    private const val TYPE_OFFSET = 4

    /**
     * Writes a frame and flushes.
     *
     * Synchronized on [out] because the writer thread and a session teardown can
     * both emit frames; interleaving half a header would desynchronize the stream.
     */
    fun write(
        out: OutputStream,
        type: FrameType,
        payload: ByteArray,
    ) {
        require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size} exceeds $MAX_PAYLOAD" }

        val header = ByteBuffer.allocate(HEADER_SIZE)
        header.putInt(payload.size)
        header.put(type.code)

        synchronized(out) {
            out.write(header.array())
            out.write(payload)
            out.flush()
        }
    }

    /**
     * Reads one frame. Returns null at a clean end of stream.
     *
     * @throws FrameTooLargeException when the declared length exceeds the cap, so
     *   the caller closes the connection rather than allocating.
     * @throws EOFException when the stream ends mid-header or mid-payload.
     */
    fun read(input: InputStream): Frame? {
        val header = ByteArray(HEADER_SIZE)
        val headerRead = readFully(input, header, 0, HEADER_SIZE)
        if (headerRead == 0) return null
        if (headerRead < HEADER_SIZE) throw EOFException("stream ended mid-header")

        val buffer = ByteBuffer.wrap(header)
        val length = buffer.int
        if (length < 0 || length > MAX_PAYLOAD) {
            throw FrameTooLargeException(length)
        }

        val type =
            FrameType.fromCode(buffer.get())
                ?: throw UnknownFrameTypeException(header[TYPE_OFFSET])

        val payload = ByteArray(length)
        if (length > 0 && readFully(input, payload, 0, length) < length) {
            throw EOFException("stream ended mid-payload")
        }

        return Frame(type, payload)
    }

    /**
     * Reads up to [length] bytes, returning how many arrived before EOF.
     *
     * A read timeout (an SO_TIMEOUT on the socket) arrives as an IOException rather
     * than an EOF, and the bytes already read must be kept: throwing away a partial
     * header would desynchronize the stream. The timeout is propagated only when
     * nothing at all has been read, so a caller with a socket timeout can retry;
     * mid-frame it is rethrown, because a frame that stops halfway is a broken
     * connection.
     */
    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var read = 0
        while (read < length) {
            val n =
                try {
                    input.read(buffer, offset + read, length - read)
                } catch (t: IOException) {
                    // The timeout is propagated with its cause intact so a caller
                    // can tell it apart from a broken connection.
                    if (read == 0) throw t

                    throw EOFException("stream ended mid-frame: ${t.message}").apply {
                        initCause(t)
                    }
                }
            if (n < 0) break
            read += n
        }

        return read
    }
}

/** The declared frame length exceeds [BridgeProtocol.MAX_PAYLOAD]. */
class FrameTooLargeException(
    val declared: Int,
) : IOException("frame length $declared exceeds cap")

/** The frame carried a type this build does not know. */
class UnknownFrameTypeException(
    val code: Byte,
) : IOException("unknown frame type $code")
