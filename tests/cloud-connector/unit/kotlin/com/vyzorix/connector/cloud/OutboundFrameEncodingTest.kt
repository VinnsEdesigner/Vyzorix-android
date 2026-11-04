package com.vyzorix.connector.cloud

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An outbound frame must always carry its `type` discriminator.
 *
 * The bug this guards: the shared [CloudJson] config drops default values, and
 * `type` has a default, so an encoded `command_result` had no `type` at all. The
 * server routes frames by that field, so every command result was silently lost
 * while the device reported the send as successful.
 */
class OutboundFrameEncodingTest {
    @Test
    fun `command_result carries its type`() {
        val json =
            CommandResultFrame(
                dispatchId = "d1",
                success = true,
                output = "uid=0",
            ).toJson()

        assertTrue(
            "the discriminator must be on the wire: $json",
            json.contains("\"type\":\"command_result\""),
        )
        assertTrue(json.contains("\"dispatchId\":\"d1\""))
        assertTrue(json.contains("\"success\":true"))
    }

    @Test
    fun `a failed result carries its type too`() {
        val json =
            CommandResultFrame(
                dispatchId = "d2",
                success = false,
                output = "unsupported command type: USB_ATTACH",
            ).toJson()

        assertTrue(json.contains("\"type\":\"command_result\""))
        assertTrue(json.contains("\"success\":false"))
    }

    @Test
    fun `telemetry carries its type`() {
        val json =
            CloudJson.outbound.encodeToString(
                TelemetryFrame(deviceId = "dev-1", riskScore = 7),
            )

        assertTrue(
            "telemetry must carry its discriminator: $json",
            json.contains("\"type\":\"telemetry\""),
        )
    }
}
