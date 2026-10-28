package com.vyzorix.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Token lifecycle. The clock is injected so expiry is deterministic rather than a
 * sleep.
 */
class SessionTokensTest {

    private class FakeClock(
        var now: Long = 1_000_000L,
    ) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    @Test
    fun `issued tokens are unique and validate`() {
        val registry = SessionTokenRegistry()
        val first = registry.issue("s1", "dev-1", "op-1")
        val second = registry.issue("s2", "dev-1", "op-1")

        assertNotEquals(first.token, second.token)
        assertSame(first, registry.validate(first.token))
        assertSame(second, registry.validate(second.token))
    }

    @Test
    fun `an unknown token does not validate`() {
        val registry = SessionTokenRegistry()

        assertNull(registry.validate("not-a-token"))
    }

    @Test
    fun `an empty allowlist permits every api`() {
        val registry = SessionTokenRegistry()
        val token = registry.issue("s1", "dev-1", "op-1")

        assertTrue(token.permits("battery"))
        assertTrue(token.permits("logcat"))
    }

    @Test
    fun `a scoped token permits only its allowlist`() {
        val registry = SessionTokenRegistry()
        val token = registry.issue("s1", "dev-1", "op-1", apiAllowlist = setOf("battery"))

        assertTrue(token.permits("battery"))
        assertFalse(token.permits("logcat"))
    }

    @Test
    fun `an expired token stops validating and is dropped`() {
        val clock = FakeClock()
        val registry = SessionTokenRegistry(clock = { clock.now })
        val token = registry.issue("s1", "dev-1", "op-1", ttlMs = 1000)

        assertSame(token, registry.validate(token.token))

        clock.advance(1001)

        assertNull(registry.validate(token.token))
        assertEquals(0, registry.size)
    }

    @Test
    fun `revoking a session drops every token it owns`() {
        val registry = SessionTokenRegistry()
        val a = registry.issue("s1", "dev-1", "op-1")
        val b = registry.issue("s1", "dev-1", "op-1")
        val other = registry.issue("s2", "dev-1", "op-1")

        val removed = registry.revokeSession("s1")

        assertEquals(2, removed)
        assertNull(registry.validate(a.token))
        assertNull(registry.validate(b.token))
        assertSame(other, registry.validate(other.token))
    }

    @Test
    fun `sweep drops expired and revoked tokens only`() {
        val clock = FakeClock()
        val registry = SessionTokenRegistry(clock = { clock.now })
        val shortLived = registry.issue("s1", "dev-1", "op-1", ttlMs = 10)
        val longLived = registry.issue("s2", "dev-1", "op-1", ttlMs = 1_000_000)

        clock.advance(11)

        assertEquals(1, registry.sweep())
        assertEquals(1, registry.size)
        assertSame(longLived, registry.validate(longLived.token))
        assertNull(registry.validate(shortLived.token))
    }
}
