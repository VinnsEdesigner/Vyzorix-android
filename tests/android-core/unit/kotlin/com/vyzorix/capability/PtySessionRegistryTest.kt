package com.vyzorix.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registry behaviour that needs no device: the cap, duplicate rejection, and
 * device-scoped teardown. The syscall paths are covered on-device.
 */
class PtySessionRegistryTest {

    private fun session(
        id: String,
        deviceId: String,
    ) = PtySession(
        id = id,
        masterFd = -1,
        slavePath = "/dev/pts/0",
        childPid = -1,
        deviceId = deviceId,
        operatorId = "op-1",
    )

    @Test
    fun `registers and reports counts`() {
        val registry = PtySessionRegistry()

        assertTrue(registry.register(session("s1", "dev-1")) is PtyResult.Ok)
        assertEquals(1, registry.count)
        assertEquals(1, registry.countForDevice("dev-1"))
    }

    @Test
    fun `rejects a duplicate id without consuming a slot`() {
        val registry = PtySessionRegistry()
        registry.register(session("s1", "dev-1"))

        val second = registry.register(session("s1", "dev-2"))

        assertTrue(second is PtyResult.Err)
        assertEquals(PtyError.Kind.SESSION_EXISTS, (second as PtyResult.Err).error.kind)
        assertEquals(0, registry.countForDevice("dev-2"))
        assertEquals(1, registry.count)
    }

    @Test
    fun `enforces the per-device cap`() {
        val registry = PtySessionRegistry(maxSessionsPerDevice = 2)
        registry.register(session("s1", "dev-1"))
        registry.register(session("s2", "dev-1"))

        val third = registry.register(session("s3", "dev-1"))

        assertEquals(PtyError.Kind.TOO_MANY_SESSIONS, (third as PtyResult.Err).error.kind)
        assertTrue(registry.register(session("s4", "dev-2")) is PtyResult.Ok)
    }

    @Test
    fun `unregister removes the session and frees the slot`() {
        val registry = PtySessionRegistry(maxSessionsPerDevice = 1)
        registry.register(session("s1", "dev-1"))

        assertTrue(registry.unregister("s1") is PtyResult.Ok)
        assertEquals(0, registry.countForDevice("dev-1"))
        assertTrue(registry.register(session("s2", "dev-1")) is PtyResult.Ok)
    }

    @Test
    fun `unregister of an unknown id reports no such session`() {
        val registry = PtySessionRegistry()

        val result = registry.unregister("missing")

        assertEquals(PtyError.Kind.NO_SUCH_SESSION, (result as PtyResult.Err).error.kind)
    }

    @Test
    fun `unregisterAllForDevice closes only that device`() {
        val registry = PtySessionRegistry()
        registry.register(session("s1", "dev-1"))
        registry.register(session("s2", "dev-1"))
        registry.register(session("s3", "dev-2"))

        val closed = registry.unregisterAllForDevice("dev-1")

        assertEquals(2, closed.size)
        assertEquals(0, registry.countForDevice("dev-1"))
        assertEquals(1, registry.countForDevice("dev-2"))
        assertNull(registry.get("s1"))
        assertTrue(registry.get("s3") != null)
    }

    @Test
    fun `error kinds map from native status codes`() {
        assertEquals(PtyError.Kind.SYSCALL_FAILED, PtyError.fromNative(1, "").kind)
        assertEquals(PtyError.Kind.EXEC_FAILED, PtyError.fromNative(8, "").kind)
        assertEquals(PtyError.Kind.FD_PASS_FAILED, PtyError.fromNative(9, "").kind)
        assertEquals(PtyError.Kind.INTERNAL, PtyError.fromNative(99, "").kind)
    }
}
