package com.vyzorix.connector.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The binary PTY frame format must match the server's `pty_frame.go` exactly, or
 * terminal traffic is silently mis-framed. These tests pin the wire bytes with
 * literals the server would produce.
 */
class PtyFrameTest {
    @Test
    fun `header is channel type and a big-endian length`() {
        val frame = PtyFrame.encode(PtyFrame.TYPE_CLOSE, ByteArray(0))

        assertEquals(4, frame.size)
        assertEquals(PtyFrame.CHANNEL_PTY, frame[0])
        assertEquals(PtyFrame.TYPE_CLOSE, frame[1])
        // Big-endian zero length.
        assertEquals(0, frame[2].toInt())
        assertEquals(0, frame[3].toInt())
    }

    @Test
    fun `length is big-endian`() {
        val payload = ByteArray(0x0102) { 0x41 }
        val frame = PtyFrame.encode(PtyFrame.TYPE_CLOSE, payload)

        assertEquals(0x01, frame[2].toInt())
        assertEquals(0x02, frame[3].toInt())
    }

    @Test
    fun `data frame round-trips with its session header`() {
        val data = "hello".toByteArray()
        val frame = PtyFrame.encodeData("sess-1", data)

        val decoded = PtyFrame.decode(frame)

        assertEquals(PtyFrame.CHANNEL_PTY, decoded.channel)
        assertEquals(PtyFrame.TYPE_DATA, decoded.type)
        assertEquals("sess-1", decoded.sessionId)
        assertArrayEquals(data, decoded.payload)
    }

    @Test
    fun `data payload is session-prefixed as the server writes it`() {
        // [1B idLen][idLen id][data]. Pinned literally: the server reads the length
        // byte first, so an off-by-one here mis-routes every keystroke.
        val frame = PtyFrame.encodeData("ab", byteArrayOf(0x58))

        assertEquals(PtyFrame.CHANNEL_PTY, frame[0])
        assertEquals(PtyFrame.TYPE_DATA, frame[1])
        // payload length = 1 (idLen) + 2 (id) + 1 (data) = 4, big-endian.
        assertEquals(0, frame[2].toInt())
        assertEquals(4, frame[3].toInt())
        assertEquals(2, frame[4].toInt()) // idLen
        assertEquals('a'.code.toByte(), frame[5])
        assertEquals('b'.code.toByte(), frame[6])
        assertEquals(0x58.toByte(), frame[7])
    }

    @Test
    fun `resize frame carries rows then cols as big-endian uint16`() {
        val frame = PtyFrame.encodeResize("sess-1", rows = 24, cols = 80)

        val decoded = PtyFrame.decode(frame)
        assertEquals(PtyFrame.TYPE_RESIZE, decoded.type)
        assertEquals("sess-1", decoded.sessionId)

        val (rows, cols) = PtyFrame.decodeResize(decoded.payload)
        assertEquals(24, rows)
        assertEquals(80, cols)
    }

    @Test
    fun `close frame carries its session id`() {
        // A close names its session: one device multiplexes several terminals, so a
        // close without an id would be unroutable. The server's own close path sends
        // EncodeSessionData, and the device-side handler reads it with
        // DecodeSessionData, so the decoder must read it here too.
        val frame = PtyFrame.encode(PtyFrame.TYPE_CLOSE, ByteArray(0), "sess-1")

        val decoded = PtyFrame.decode(frame)
        assertEquals(PtyFrame.TYPE_CLOSE, decoded.type)
        assertEquals("sess-1", decoded.sessionId)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `close carries an exit code payload when present`() {
        val frame = PtyFrame.encode(PtyFrame.TYPE_CLOSE, "0".toByteArray(), "sess-1")

        val decoded = PtyFrame.decode(frame)
        assertEquals("sess-1", decoded.sessionId)
        assertEquals("0", String(decoded.payload))
    }

    @Test
    fun `a frame shorter than the header is refused`() {
        assertThrows(PtyFrame.MalformedFrame::class.java) {
            PtyFrame.decode(byteArrayOf(0x01, 0x01))
        }
    }

    @Test
    fun `a declared length beyond the max is refused`() {
        // [channel][type][0xFFFF] with no payload: the bound exists so a peer cannot
        // make the other side allocate for a frame it never sent.
        val raw =
            byteArrayOf(PtyFrame.CHANNEL_PTY, PtyFrame.TYPE_DATA, 0xFF.toByte(), 0xFF.toByte())

        assertThrows(PtyFrame.MalformedFrame::class.java) {
            PtyFrame.decode(raw)
        }
    }

    @Test
    fun `a truncated payload is refused`() {
        // Declares 10 bytes but carries 2.
        val raw = byteArrayOf(PtyFrame.CHANNEL_PTY, PtyFrame.TYPE_CLOSE, 0x00, 0x0A, 0x41, 0x42)

        assertThrows(PtyFrame.MalformedFrame::class.java) {
            PtyFrame.decode(raw)
        }
    }

    @Test
    fun `a session id longer than the max is refused`() {
        val tooLong = "x".repeat(PtyFrame.MAX_SESSION_ID_LEN + 1)
        assertThrows(IllegalArgumentException::class.java) {
            PtyFrame.encodeData(tooLong, byteArrayOf(0x01))
        }
    }

    @Test
    fun `the max payload is the bound on a single frame`() {
        val atLimit = ByteArray(PtyFrame.MAX_PAYLOAD)
        // No session header, so the payload is exactly at the bound.
        val frame = PtyFrame.encode(PtyFrame.TYPE_CLOSE, atLimit)
        assertTrue(frame.size == PtyFrame.HEADER_SIZE + PtyFrame.MAX_PAYLOAD)

        assertThrows(IllegalArgumentException::class.java) {
            PtyFrame.encode(PtyFrame.TYPE_CLOSE, ByteArray(PtyFrame.MAX_PAYLOAD + 1))
        }
    }
}
