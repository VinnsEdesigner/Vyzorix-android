package com.vyzorix.connector.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The spike (REGISTRATION_CONTRACT.md §12): the canonical strings the device builds
 * must equal the server's, byte for byte.
 *
 * Each expected value here was produced by the Go implementation — the same
 * functions the server signs with — so a drift in either language fails this test
 * rather than a device in the field. The fixtures are literal strings on purpose:
 * computing the expected value in Kotlin would only prove the Kotlin agrees with
 * itself.
 */
class SignaturesTest {
    @Test
    fun `connect canonical matches the server format`() {
        // Server: ConnectCanonical = "connect|" + deviceId + "|" + timestamp + "|" + nonce
        val got = Signatures.connect("dev-1", "1767225600000", "n-1")

        assertEquals("connect|dev-1|1767225600000|n-1", got)
    }

    @Test
    fun `command canonical matches the server format`() {
        // Server: "command|" + dispatchId + "|" + deviceId + "|" + command + "|" +
        //         timestamp + "|" + nonce + "|" + args
        val got =
            Signatures.command(
                dispatchId = "d1",
                deviceId = "dev-1",
                command = "SHELL_EXEC",
                timestampMs = 1767225600000,
                nonce = "n-1",
                args = """{"cmd":"id"}""",
            )

        assertEquals("command|d1|dev-1|SHELL_EXEC|1767225600000|n-1|{\"cmd\":\"id\"}", got)
    }

    @Test
    fun `command canonical with empty args uses the literal braces`() {
        // The server substitutes "{}" when args are empty, before signing. A device
        // that signed an empty string would never match.
        val got =
            Signatures.command(
                dispatchId = "d1",
                deviceId = "dev-1",
                command = "SHELL_EXEC",
                timestampMs = 1767225600000,
                nonce = "n-1",
                args = "",
            )

        assertEquals("command|d1|dev-1|SHELL_EXEC|1767225600000|n-1|{}", got)
    }

    @Test
    fun `command canonical with an empty dispatch id matches a config frame`() {
        // A config frame carries no dispatch id; the server signs the empty string
        // there, so the device must too.
        val got =
            Signatures.command(
                dispatchId = "",
                deviceId = "dev-1",
                command = "DEVICE_CONFIG",
                timestampMs = 1767225600000,
                nonce = "n-1",
                args = """{"version":1}""",
            )

        assertEquals("command||dev-1|DEVICE_CONFIG|1767225600000|n-1|{\"version\":1}", got)
    }

    @Test
    fun `device request canonical matches the server format`() {
        // Server: UPPER(method) + "\n" + path + "\n" + deviceId + "\n" + ts + "\n" +
        //         nonce + "\n" + base64(sha256(body))
        val got =
            Signatures.deviceRequest(
                method = "post",
                path = "/v1/device/dev-1/command",
                deviceId = "dev-1",
                timestampMs = "1767225600000",
                nonce = "n-1",
                bodyHashBase64 = "aGVsbG8=",
            )

        val expected =
            "POST\n" +
                "/v1/device/dev-1/command\n" +
                "dev-1\n" +
                "1767225600000\n" +
                "n-1\n" +
                "aGVsbG8="

        assertEquals(expected, got)
    }

    @Test
    fun `method is upper-cased`() {
        val lower = Signatures.deviceRequest("get", "/x", "d", "1", "n", "h")
        val upper = Signatures.deviceRequest("GET", "/x", "d", "1", "n", "h")

        assertEquals(upper, lower)
    }
}
