package com.vyzorix.connector.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client's WebSocket heartbeat.
 *
 * The server pings and reaps a silent peer, which keeps the *server* from dropping
 * an idle device — but it does not tell the *device* that a mobile network has
 * silently dropped the socket. OkHttp's `pingInterval` is the client half: it sends
 * its own ping and fails the socket when the pong does not come, which is what the
 * reconnect loop needs.
 */
class DeviceHeartbeatTest {
    @Test
    fun `the default ping interval beats the server's reap window`() {
        // The server's pongWait is 70s, so a 20s client ping always arrives first.
        assertEquals(20L, DeviceWsClient.Heartbeat.DEFAULT_PING_INTERVAL_SECONDS)
        assertTrue(DeviceWsClient.Heartbeat.DEFAULT_PING_INTERVAL_SECONDS < 70L)
    }

    @Test
    fun `the built client carries the ping interval`() {
        val client = DeviceWsClient.buildHttpClient()

        assertEquals(20_000, client.pingIntervalMillis)
    }

    @Test
    fun `a custom interval is applied`() {
        val client = DeviceWsClient.buildHttpClient(DeviceWsClient.Heartbeat(intervalSeconds = 15))

        assertEquals(15_000, client.pingIntervalMillis)
    }

    @Test
    fun `a non-positive interval is refused`() {
        // A zero or negative ping interval would disable the heartbeat silently,
        // which is the one thing it exists to prevent.
        val thrown =
            runCatching { DeviceWsClient.Heartbeat(intervalSeconds = 0) }
                .exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `the heartbeat is per-instance and does not leak between clients`() {
        val short = DeviceWsClient.buildHttpClient(DeviceWsClient.Heartbeat(intervalSeconds = 5))
        val long = DeviceWsClient.buildHttpClient(DeviceWsClient.Heartbeat(intervalSeconds = 30))

        assertEquals(5_000, short.pingIntervalMillis)
        assertEquals(30_000, long.pingIntervalMillis)
        assertFalse(short.pingIntervalMillis == long.pingIntervalMillis)
    }
}
