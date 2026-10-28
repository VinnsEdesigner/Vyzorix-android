package com.vyzorix.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON control shapes and the PTY channel prefix. A wrong shape here is a
 * client that cannot talk to the bridge at all.
 */
class BridgeMessagesTest {

    @Test
    fun `a request round trips`() {
        val json =
            BridgeJson.encodeToString(
                Request.serializer(),
                Request(
                    "7",
                    "battery",
                    mapOf(
                        "x" to "y",
                    ),
                ),
            )

        val back = BridgeJson.decodeFromString<Request>(json)

        assertEquals("7", back.id)
        assertEquals("battery", back.api)
        assertEquals("y", back.args["x"])
    }

    @Test
    fun `an ok response carries a result and no error`() {
        val json =
            BridgeJson.encodeToString(
                Response.serializer(),
                Response("7", ok = true, result = "{\"level\":50}"),
            )

        val back = BridgeJson.decodeFromString<Response>(json)

        assertTrue(back.ok)
        assertNull(back.error)
    }

    @Test
    fun `an error response carries a kind and detail`() {
        val json =
            BridgeJson.encodeToString(
                Response.serializer(),
                Response("7", ok = false, error = BridgeError(BridgeErrorKind.FORBIDDEN, "nope")),
            )

        val back = BridgeJson.decodeFromString<Response>(json)

        assertEquals(BridgeErrorKind.FORBIDDEN, back.error?.kind)
    }

    @Test
    fun `an auth result round trips`() {
        val json = BridgeJson.encodeToString(AuthResult.serializer(), AuthResult("s1"))

        assertEquals("s1", BridgeJson.decodeFromString<AuthResult>(json).session)
    }

    @Test
    fun `an unknown field is ignored so a newer client can add fields`() {
        val back = BridgeJson.decodeFromString<Request>("{\"id\":\"1\",\"api\":\"x\",\"future\":1}")

        assertEquals("1", back.id)
    }

    @Test
    fun `a pty frame round trips with its channel`() {
        val encoded = PtyFrame.encode(5, byteArrayOf(1, 2, 3))

        val decoded = PtyFrame.decode(encoded)!!

        assertEquals(5, decoded.first)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.second)
    }

    @Test
    fun `a channel byte above 127 survives the round trip`() {
        val decoded = PtyFrame.decode(PtyFrame.encode(200, byteArrayOf(9)))!!

        assertEquals(200, decoded.first)
    }

    @Test
    fun `an empty payload has no channel`() {
        assertNull(PtyFrame.decode(ByteArray(0)))
    }

    @Test
    fun `a channel-only frame decodes to empty bytes`() {
        val decoded = PtyFrame.decode(PtyFrame.encode(1, ByteArray(0)))!!

        assertEquals(1, decoded.first)
        assertEquals(0, decoded.second.size)
    }
}
