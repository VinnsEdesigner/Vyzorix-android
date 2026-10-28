package com.vyzorix.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing is the one place a wrong byte desynchronizes every later frame, so it is
 * tested on the JVM where it needs no device.
 */
class BridgeProtocolTest {

    private fun frame(
        type: Byte,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(5)
        header.putInt(payload.size)
        header.put(type)
        out.write(header.array())
        out.write(payload)

        return out.toByteArray()
    }

    @Test
    fun `round trips a frame`() {
        val out = ByteArrayOutputStream()
        BridgeProtocol.write(out, FrameType.Request, "hello".toByteArray())

        val read = BridgeProtocol.read(ByteArrayInputStream(out.toByteArray()))

        assertEquals(FrameType.Request, read?.type)
        assertArrayEquals("hello".toByteArray(), read?.payload)
    }

    @Test
    fun `round trips every frame type`() {
        for (type in FrameType.entries) {
            val out = ByteArrayOutputStream()
            BridgeProtocol.write(out, type, byteArrayOf(1, 2, 3))

            val read = BridgeProtocol.read(ByteArrayInputStream(out.toByteArray()))

            assertEquals(type, read?.type)
            assertArrayEquals(byteArrayOf(1, 2, 3), read?.payload)
        }
    }

    @Test
    fun `round trips an empty payload`() {
        val out = ByteArrayOutputStream()
        BridgeProtocol.write(out, FrameType.PtyOutput, ByteArray(0))

        val read = BridgeProtocol.read(ByteArrayInputStream(out.toByteArray()))

        assertEquals(FrameType.PtyOutput, read?.type)
        assertEquals(0, read?.payload?.size)
    }

    @Test
    fun `reads several frames from one stream`() {
        val out = ByteArrayOutputStream()
        BridgeProtocol.write(out, FrameType.Request, "one".toByteArray())
        BridgeProtocol.write(out, FrameType.PtyInput, "two".toByteArray())

        val input = ByteArrayInputStream(out.toByteArray())
        val first = BridgeProtocol.read(input)
        val second = BridgeProtocol.read(input)

        assertEquals("one", String(first!!.payload))
        assertEquals("two", String(second!!.payload))
        assertNull(BridgeProtocol.read(input))
    }

    @Test
    fun `a declared length over the cap is rejected, not allocated`() {
        val header = ByteBuffer.allocate(5)
        header.putInt(BridgeProtocol.MAX_PAYLOAD + 1)
        header.put(FrameType.Request.code)

        val thrown =
            runCatching { BridgeProtocol.read(ByteArrayInputStream(header.array())) }
                .exceptionOrNull()

        assertTrue("expected FrameTooLargeException, got $thrown", thrown is FrameTooLargeException)
    }

    @Test
    fun `a negative declared length is rejected`() {
        val header = ByteBuffer.allocate(5)
        header.putInt(-1)
        header.put(FrameType.Request.code)

        val thrown =
            runCatching { BridgeProtocol.read(ByteArrayInputStream(header.array())) }
                .exceptionOrNull()

        assertTrue("expected FrameTooLargeException, got $thrown", thrown is FrameTooLargeException)
    }

    @Test
    fun `an unknown type code is rejected`() {
        val header = ByteBuffer.allocate(5)
        header.putInt(0)
        header.put(0x7F.toByte())

        val thrown =
            runCatching { BridgeProtocol.read(ByteArrayInputStream(header.array())) }
                .exceptionOrNull()

        assertTrue(
            "expected UnknownFrameTypeException, got $thrown",
            thrown is UnknownFrameTypeException,
        )
    }

    @Test
    fun `a truncated header is an EOF, not a hang`() {
        val thrown =
            runCatching { BridgeProtocol.read(ByteArrayInputStream(byteArrayOf(0, 0))) }
                .exceptionOrNull()

        assertTrue("expected EOFException, got $thrown", thrown is EOFException)
    }

    @Test
    fun `a truncated payload is an EOF`() {
        val full = frame(FrameType.Request.code, "abcdef".toByteArray())
        val truncated = full.copyOf(full.size - 2)

        val thrown =
            runCatching { BridgeProtocol.read(ByteArrayInputStream(truncated)) }
                .exceptionOrNull()

        assertTrue("expected EOFException, got $thrown", thrown is EOFException)
    }

    @Test
    fun `writing a payload over the cap is refused`() {
        val thrown =
            runCatching {
                BridgeProtocol.write(
                    ByteArrayOutputStream(),
                    FrameType.Request,
                    ByteArray(BridgeProtocol.MAX_PAYLOAD + 1),
                )
            }.exceptionOrNull()

        assertTrue(
            "expected IllegalArgumentException, got $thrown",
            thrown is IllegalArgumentException,
        )
    }
}
