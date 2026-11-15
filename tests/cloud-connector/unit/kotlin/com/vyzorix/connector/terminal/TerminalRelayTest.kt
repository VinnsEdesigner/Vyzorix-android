package com.vyzorix.connector.terminal

import com.vyzorix.connector.cloud.PtyFrame
import com.vyzorix.service.PtySessionPort
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay's routing rules, exercised with a fake port and a fake socket.
 *
 * The relay is pure routing, so every branch can be driven without a device: what
 * matters is that a frame reaches the right session, that a malformed frame is
 * refused rather than acted on, and that the session lifecycle (open once, close
 * once, close on exit) cannot leak a shell.
 */
class TerminalRelayTest {
    /** A recording port: every call is captured, and open/close can be forced to fail. */
    private class FakePort : PtySessionPort {
        val opened = mutableListOf<String>()

        /** The identity each open carried, so a test can assert it arrived. */
        val identities = mutableListOf<Pair<String, com.vyzorix.service.SessionIdentity?>>()
        val written = mutableListOf<Pair<String, ByteArray>>()
        val resized = mutableListOf<Triple<String, Int, Int>>()
        val closed = mutableListOf<String>()

        var failOpen = false

        /** Captured callbacks so a test can simulate output and exit. */
        var onOutput: ((String, ByteArray) -> Unit)? = null
        var onExit: ((String, Int) -> Unit)? = null

        override fun open(
            sessionId: String,
            rows: Int,
            cols: Int,
            identity: com.vyzorix.service.SessionIdentity?,
            onOutput: (String, ByteArray) -> Unit,
            onExit: (String, Int) -> Unit,
        ): Boolean {
            if (failOpen) return false

            identities += sessionId to identity

            opened += sessionId
            resized += Triple(sessionId, rows, cols)
            this.onOutput = onOutput
            this.onExit = onExit

            return true
        }

        override fun write(
            sessionId: String,
            data: ByteArray,
        ): Boolean {
            written += sessionId to data

            return true
        }

        override fun resize(
            sessionId: String,
            rows: Int,
            cols: Int,
        ): Boolean {
            resized += Triple(sessionId, rows, cols)

            return true
        }

        override fun close(sessionId: String) {
            closed += sessionId
        }

        override fun isOpen(sessionId: String): Boolean =
            sessionId in opened && sessionId !in closed
    }

    private class FakeSocket {
        val sent = mutableListOf<ByteArray>()
        var accepts = true

        fun send(frame: ByteArray): Boolean {
            if (!accepts) return false
            sent += frame

            return true
        }

        /** Decoded frames, for assertions. */
        fun decoded(): List<PtyFrame.Frame> = sent.map { PtyFrame.decode(it) }
    }

    private fun relay(
        port: FakePort = FakePort(),
        socket: FakeSocket = FakeSocket(),
        closed: MutableList<Pair<String, String>> = mutableListOf(),
        now: () -> Long = System::currentTimeMillis,
        detachedTtlMs: Long = TerminalRelay.DETACHED_TTL_MS,
        bufferBytes: Int = TerminalRelay.DETACHED_BUFFER_BYTES,
    ): Triple<TerminalRelay, FakePort, FakeSocket> {
        val r =
            TerminalRelay(
                port = port,
                sendFrame = socket::send,
                onSessionClosed = { id, reason -> closed += id to reason },
                detachedTtlMs = detachedTtlMs,
                now = now,
                detachedBufferBytes = bufferBytes,
            )

        return Triple(r, port, socket)
    }

    private fun openFrame(
        sessionId: String,
        rows: Int = 24,
        cols: Int = 80,
    ): ByteArray =
        PtyFrame.encode(
            PtyFrame.TYPE_OPEN,
            PtyFrame.resizePayload(rows, cols),
            sessionId,
        )

    /** An OPEN payload the server builds: window size then the identity JSON. */
    private fun openFrameWithIdentity(
        sessionId: String,
        identityJson: String,
        rows: Int = 24,
        cols: Int = 80,
    ): ByteArray {
        val size = PtyFrame.resizePayload(rows, cols)
        val payload = size + identityJson.toByteArray(Charsets.UTF_8)

        return PtyFrame.encode(PtyFrame.TYPE_OPEN, payload, sessionId)
    }

    @Test
    fun `an open frame carries the user identity to the port`() {
        val (r, port, _) = relay()

        r.onFrame(
            openFrameWithIdentity(
                "s1",
                """{"user":"op-1","name":"Kamau","role":"operator",""" +
                    """"roleLabel":"Operator","allowlist":["camera_list","battery"]}""",
            ),
        )

        val identity = port.identities.single().second
        assertEquals("op-1", identity?.user)
        assertEquals("Operator", identity?.roleLabel)
        assertEquals(listOf("camera_list", "battery"), identity?.allowlist)
    }

    @Test
    fun `an open frame without an identity opens an unattributed session`() {
        // A server that predates the identity block: the shell still opens, and the
        // port is told the user is unknown rather than being handed a wrong one.
        val (r, port, _) = relay()

        r.onFrame(openFrame("s1"))

        assertTrue(r.isOpen("s1"))
        assertEquals(null, port.identities.single().second)
    }

    @Test
    fun `an open frame starts a session sized from the frame`() {
        val (r, port, _) = relay()

        assertTrue(r.onFrame(openFrame("s1", rows = 40, cols = 120)))

        assertEquals(listOf("s1"), port.opened)
        assertEquals(Triple("s1", 40, 120), port.resized.first())
        assertTrue(r.isOpen("s1"))
        assertEquals(1, r.openCount())
    }

    @Test
    fun `output is sent as a DATA frame carrying the session id`() {
        val (r, port, socket) = relay()
        r.onFrame(openFrame("s1"))

        port.onOutput?.invoke("s1", "hello".toByteArray())

        val frame = socket.decoded().single()
        assertEquals(PtyFrame.TYPE_DATA, frame.type)
        assertEquals("s1", frame.sessionId)
        assertArrayEquals("hello".toByteArray(), frame.payload)
    }

    @Test
    fun `keystrokes reach the session`() {
        val (r, port, _) = relay()
        r.onFrame(openFrame("s1"))

        r.onFrame(PtyFrame.encodeData("s1", "ls\n".toByteArray()))

        assertEquals(1, port.written.size)
        assertEquals("s1", port.written.first().first)
        assertArrayEquals("ls\n".toByteArray(), port.written.first().second)
    }

    @Test
    fun `a resize frame resizes the session`() {
        val (r, port, _) = relay()
        r.onFrame(openFrame("s1"))

        r.onFrame(PtyFrame.encodeResize("s1", rows = 50, cols = 200))

        assertEquals(Triple("s1", 50, 200), port.resized.last())
    }

    @Test
    fun `a close frame reaps the session and reports it`() {
        val closed = mutableListOf<Pair<String, String>>()
        val (r, port, _) = relay(closed = closed)
        r.onFrame(openFrame("s1"))

        r.onFrame(PtyFrame.encode(PtyFrame.TYPE_CLOSE, ByteArray(0), "s1"))

        assertEquals(listOf("s1"), port.closed)
        assertEquals(1, closed.size)
        assertEquals("s1" to "user_closed", closed.first())
        assertFalse(r.isOpen("s1"))
    }

    @Test
    fun `a close frame with no session id is refused, not guessed`() {
        // The device multiplexes sessions; a close that does not name one could only
        // be applied by guessing, and with two terminals open that reaps the wrong
        // shell.
        val (r, port, _) = relay()
        r.onFrame(openFrame("s1"))

        val nameless = PtyFrame.encode(PtyFrame.TYPE_CLOSE, ByteArray(0))
        assertFalse(r.onFrame(nameless))

        assertEquals(emptyList<String>(), port.closed)
        assertTrue(r.isOpen("s1"))
    }

    @Test
    fun `a duplicate open re-binds the existing shell, it does not start a second`() {
        val (r, port, _) = relay()

        r.onFrame(openFrame("s1"))
        r.onFrame(openFrame("s1"))

        assertEquals(listOf("s1"), port.opened)
    }

    @Test
    fun `two sessions are routed independently`() {
        val (r, port, _) = relay()
        r.onFrame(openFrame("s1"))
        r.onFrame(openFrame("s2"))

        r.onFrame(PtyFrame.encodeData("s2", "x".toByteArray()))

        assertEquals(listOf("s1", "s2"), port.opened)
        assertEquals("s2", port.written.single().first)
        assertEquals(2, r.openCount())
    }

    @Test
    fun `a frame for an unknown session is refused`() {
        val (r, port, _) = relay()

        assertFalse(r.onFrame(PtyFrame.encodeData("nope", "x".toByteArray())))
        assertEquals(emptyList<Pair<String, ByteArray>>(), port.written)
    }

    @Test
    fun `a malformed frame is refused without throwing`() {
        val (r, _, _) = relay()

        // Shorter than a header.
        assertFalse(r.onFrame(byteArrayOf(0x01, 0x01)))
        // An unknown channel.
        assertFalse(r.onFrame(PtyFrame.encode(PtyFrame.TYPE_DATA, "x".toByteArray(), "s1")))
    }

    @Test
    fun `a failed open reports an error to the server`() {
        val port = FakePort().apply { failOpen = true }
        val (r, _, socket) = relay(port = port)

        r.onFrame(openFrame("s1"))

        // ERROR is not session-prefixed: the server reads its payload as a raw
        // detail string, so the session id is logged device-side, not on the wire.
        val frame = socket.decoded().single()
        assertEquals(PtyFrame.TYPE_ERROR, frame.type)
        assertEquals("", frame.sessionId)
        assertEquals("device could not open a shell", String(frame.payload))
        assertFalse(r.isOpen("s1"))
    }

    @Test
    fun `a shell that exits on its own closes the session and tells the server`() {
        val closed = mutableListOf<Pair<String, String>>()
        val (r, port, socket) = relay(closed = closed)
        r.onFrame(openFrame("s1"))

        port.onExit?.invoke("s1", 0)

        val close = socket.decoded().single()
        assertEquals(PtyFrame.TYPE_CLOSE, close.type)
        assertEquals("s1", close.sessionId)
        assertEquals("0", String(close.payload))
        assertFalse(r.isOpen("s1"))
        assertEquals("s1" to "shell_exited", closed.first())
    }

    @Test
    fun `a failed socket write detaches the session rather than closing it`() {
        // The socket is gone but the shell is not: the session is detached so its
        // output is kept for a reconnect, and it stays open.
        val closed = mutableListOf<Pair<String, String>>()
        val socket = FakeSocket().apply { accepts = false }
        val (r, port, _) = relay(socket = socket, closed = closed)
        r.onFrame(openFrame("s1"))

        port.onOutput?.invoke("s1", "output nobody is reading yet".toByteArray())

        assertTrue("the shell must survive the socket drop", r.isOpen("s1"))
        assertEquals(emptyList<String>(), port.closed)
        assertEquals(emptyList<Pair<String, String>>(), closed)
    }

    @Test
    fun `output while detached is buffered, and reattach flushes it`() {
        val socket = FakeSocket()
        val (r, port, _) = relay(socket = socket)
        r.onFrame(openFrame("s1"))

        // The socket drops, so the connection detaches the session. Output produced
        // after that is buffered rather than sent.
        r.detachAll()
        port.onOutput?.invoke("s1", "first".toByteArray())
        port.onOutput?.invoke("s1", "second".toByteArray())
        assertEquals(emptyList<ByteArray>(), socket.sent)

        // The socket is back and the browser re-opens the same id.
        r.onFrame(openFrame("s1"))

        // One shell, and the buffered output arrives in order.
        assertEquals(listOf("s1"), port.opened)
        val payloads = socket.decoded().map { String(it.payload) }
        assertEquals(listOf("first", "second"), payloads)
    }

    @Test
    fun `the write that discovers a dropped socket detaches, losing only that frame`() {
        // A failed write is how the device finds out the socket is gone. The bytes in
        // that frame are already handed to a dead socket and cannot be buffered — but
        // the session is detached rather than closed, so everything after survives.
        val socket = FakeSocket().apply { accepts = false }
        val (r, port, _) = relay(socket = socket)
        r.onFrame(openFrame("s1"))

        port.onOutput?.invoke("s1", "lost".toByteArray()) // detaches
        port.onOutput?.invoke("s1", "kept".toByteArray()) // buffered

        socket.accepts = true
        r.onFrame(openFrame("s1"))

        val payloads = socket.decoded().map { String(it.payload) }
        assertEquals(listOf("kept"), payloads)
    }

    @Test
    fun `the detached buffer drops the oldest bytes past its bound`() {
        val socket = FakeSocket()
        val (r, port, _) =
            relay(
                socket = socket,
                bufferBytes = 10,
            )
        r.onFrame(openFrame("s1"))
        r.detachAll()

        // Push more than the bound. The newest bytes are what a reconnecting user
        // needs, so the oldest are the ones dropped.
        port.onOutput?.invoke("s1", "AAAA".toByteArray())
        port.onOutput?.invoke("s1", "BBBB".toByteArray())
        port.onOutput?.invoke("s1", "CCCC".toByteArray())

        r.onFrame(openFrame("s1"))

        val payloads = socket.decoded().map { String(it.payload) }
        assertEquals(listOf("BBBB", "CCCC"), payloads)
    }

    @Test
    fun `input while detached is dropped, not written to the shell`() {
        val (r, port, _) = relay()
        r.onFrame(openFrame("s1"))
        r.detachAll()

        r.onFrame(PtyFrame.encodeData("s1", "rm -rf /\n".toByteArray()))

        assertEquals(emptyList<Pair<String, ByteArray>>(), port.written)
    }

    @Test
    fun `detachAll keeps every session running`() {
        val (r, port, _) = relay()
        r.onFrame(openFrame("s1"))
        r.onFrame(openFrame("s2"))

        r.detachAll()

        assertTrue(r.isOpen("s1"))
        assertTrue(r.isOpen("s2"))
        assertEquals(2, r.openCount())
        assertEquals(emptyList<String>(), port.closed)
    }

    @Test
    fun `a detached session past its TTL is reaped`() {
        var clock = 1_000L
        val (r, port, _) = relay(now = { clock }, detachedTtlMs = 100L)
        r.onFrame(openFrame("s1"))
        r.detachAll()

        // Inside the TTL: kept.
        clock = 1_050L
        assertEquals(0, r.reapDetached())
        assertTrue(r.isOpen("s1"))

        // Past the TTL: reaped.
        clock = 1_200L
        assertEquals(1, r.reapDetached())
        assertFalse(r.isOpen("s1"))
        assertEquals(listOf("s1"), port.closed)
    }

    @Test
    fun `an attached session is never reaped by the detached TTL`() {
        var clock = 1_000L
        val (r, _, _) = relay(now = { clock }, detachedTtlMs = 100L)
        r.onFrame(openFrame("s1"))

        clock = 10_000L
        assertEquals(0, r.reapDetached())
        assertTrue(r.isOpen("s1"))
    }

    @Test
    fun `a size-less reattach keeps the shell's size`() {
        // A server-initiated reattach sends an OPEN with no window size, so the shell
        // must keep the size it had rather than shrink to the default.
        val socket = FakeSocket()
        val (r, port, _) = relay(socket = socket)
        r.onFrame(openFrame("s1", rows = 50, cols = 200))
        r.detachAll()

        // Reattach with no size in the payload.
        r.onFrame(PtyFrame.encode(PtyFrame.TYPE_OPEN, ByteArray(0), "s1"))

        assertEquals(Triple("s1", 50, 200), port.resized.last())
    }

    @Test
    fun `a reattach carrying a size applies it`() {
        val (r, port, _) = relay()
        r.onFrame(openFrame("s1", rows = 24, cols = 80))
        r.detachAll()

        r.onFrame(openFrame("s1", rows = 60, cols = 240))

        assertEquals(Triple("s1", 60, 240), port.resized.last())
    }

    @Test
    fun `closeAll reaps every session`() {
        val (r, port, _) = relay()
        r.onFrame(openFrame("s1"))
        r.onFrame(openFrame("s2"))

        r.closeAll("connection_stopped")

        assertEquals(setOf("s1", "s2"), port.closed.toSet())
        assertEquals(0, r.openCount())
    }

    @Test
    fun `an ack frame is accepted and ignored`() {
        // The server never credits the device, so an ACK is not expected; if one
        // arrives it must be harmless rather than treated as a malformed frame.
        val (r, _, _) = relay()

        assertTrue(r.onFrame(PtyFrame.encodeAck(4)))
    }
}
