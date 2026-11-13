package com.vyzorix.connector.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The telemetry frame the device puts on the wire.
 *
 * The server routes by the top-level `type` and reads the fields by their exact
 * camelCase names, so a frame that carries neither is stored as nothing. These
 * tests pin the shape and, in particular, the unit of `uptime`: seconds, matching
 * the server's `TelemetryFrame.uptime`, not milliseconds.
 */
class TelemetryReporterTest {
    private val source =
        TelemetryReporter.TelemetrySource {
            TelemetryReporter.Sample(
                uptimeSeconds = 4_200,
                riskScore = 0,
                audioMode = 0,
                bufferLevel = 0,
                thermalTemp = 31.5,
                speakerOn = false,
            )
        }

    private class CapturingSend {
        val frames = mutableListOf<String>()

        fun send(frame: String): Boolean {
            frames.add(frame)

            return true
        }
    }

    private fun reporter(
        send: (String) -> Boolean,
        clock: () -> Long = { 1_767_225_600_000 },
    ) = TelemetryReporter(
        source = source,
        send = send,
        deviceId = "dev-1",
        clock = clock,
    )

    private fun parse(frame: String): JsonObject = Json.parseToJsonElement(frame) as JsonObject

    private fun JsonObject.string(name: String): String = (get(name) as JsonPrimitive).content

    private fun JsonObject.number(name: String): Double =
        (get(name) as JsonPrimitive).content.toDouble()

    @Test
    fun `the frame carries the type the server routes on`() {
        val out = CapturingSend()
        reporter(out::send).reportOnce()

        val frame = parse(out.frames.single())
        assertEquals("telemetry", frame.string("type"))
    }

    @Test
    fun `the frame carries the server's field names`() {
        val out = CapturingSend()
        reporter(out::send).reportOnce()

        val frame = parse(out.frames.single())
        assertEquals("dev-1", frame.string("deviceId"))
        assertEquals(1_767_225_600_000.0, frame.number("timestamp"), 0.0)
        assertEquals(31.5, frame.number("thermalTemp"), 0.0)
    }

    @Test
    fun `uptime is reported in seconds, not milliseconds`() {
        val out = CapturingSend()
        reporter(out::send).reportOnce()

        val frame = parse(out.frames.single())
        // 4_200 s, not 4_200_000 ms: the server's column is seconds, and a
        // millisecond reading would report a device up for decades.
        assertEquals(4_200.0, frame.number("uptime"), 0.0)
    }

    @Test
    fun `reportOnce reports whether the socket took the frame`() {
        assertTrue(reporter({ true }).reportOnce())
        assertFalse(reporter({ false }).reportOnce())
    }

    @Test
    fun `the frame carries the config version and per-app digests`() {
        // These are what the server compares against the config it expects, so a frame
        // that drops them silently makes every device read as unknown drift.
        val out = CapturingSend()
        val source =
            TelemetryReporter.TelemetrySource {
                TelemetryReporter.Sample(
                    uptimeSeconds = 10,
                    configVersion = 7,
                    appDigests = mapOf("vfd" to "47584980637a11e1"),
                )
            }
        TelemetryReporter(
            source = source,
            send = out::send,
            deviceId = "dev-1",
        ).reportOnce()

        val frame = parse(out.frames.single())
        assertEquals(7.0, frame.number("configVersion"), 0.0)
        val digests = frame["appDigests"] as JsonObject
        assertEquals("47584980637a11e1", (digests["vfd"] as JsonPrimitive).content)
    }

    @Test
    fun `a device with no installed state reports version zero and no digests`() {
        // Zero is a real state ("holds no config"), distinct from the field being
        // absent, which the server reads as "does not report". The field must be
        // present so the two are distinguishable on the wire.
        val out = CapturingSend()
        reporter(out::send).reportOnce()

        val frame = parse(out.frames.single())
        assertEquals(0.0, frame.number("configVersion"), 0.0)
        assertTrue(frame["appDigests"] is JsonObject)
    }
}
