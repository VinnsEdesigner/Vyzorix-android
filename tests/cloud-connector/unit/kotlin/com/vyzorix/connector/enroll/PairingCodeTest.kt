package com.vyzorix.connector.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    @Test
    fun `grouping splits an eight character code in fours`() {
        assertEquals("K7QM-3X2P", PairingCode.group("K7QM3X2P"))
    }

    @Test
    fun `a non-standard length is left alone`() {
        assertEquals("ABC", PairingCode.group("ABC"))
    }

    @Test
    fun `from uses the server's display when present`() {
        val code = PairingCode.from("K7QM3X2P", "K7QM-3X2P", 1_000, 10)

        assertEquals("K7QM-3X2P", code.display)
    }

    @Test
    fun `from groups the code when the server sent no display`() {
        val code = PairingCode.from("K7QM3X2P", "", 1_000, 10)

        assertEquals("K7QM-3X2P", code.display)
    }

    @Test
    fun `expiry is inclusive of the deadline`() {
        val code = PairingCode.from("K7QM3X2P", "", expiresAt = 1_000, expiresInSeconds = 1)

        assertFalse(code.isExpired(999))
        assertTrue(code.isExpired(1_000))
        assertTrue(code.isExpired(1_001))
    }

    @Test
    fun `seconds remaining never goes negative`() {
        val code = PairingCode.from("K7QM3X2P", "", expiresAt = 1_000, expiresInSeconds = 1)

        assertEquals(0, code.secondsRemaining(5_000))
        assertEquals(1, code.secondsRemaining(0))
    }
}
