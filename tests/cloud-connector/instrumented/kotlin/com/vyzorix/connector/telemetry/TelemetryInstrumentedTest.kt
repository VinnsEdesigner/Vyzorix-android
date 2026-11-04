package com.vyzorix.connector.telemetry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The telemetry path on a real device: the platform facts and the frame they become.
 *
 * The unit tests prove the frame shape with a synthetic source; this proves the half
 * they cannot — that [DeviceTelemetrySource] reads what the platform actually
 * reports, and that the reading survives the trip to the wire. It is instrumented
 * because it touches `SystemClock` and `BatteryManager`, which do not exist on the
 * JVM.
 */
@RunWith(AndroidJUnit4::class)
class TelemetryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun parse(frame: String): JsonObject = Json.parseToJsonElement(frame) as JsonObject

    private fun JsonObject.number(name: String): Double =
        (get(name) as JsonPrimitive).content.toDouble()

    private fun JsonObject.string(name: String): String = (get(name) as JsonPrimitive).content

    @Test
    fun thePlatformSourceReadsUptimeInSeconds() {
        val sample = DeviceTelemetrySource(context).sample()

        // The device has been up for at least a moment, and the reading is seconds:
        // a millisecond value would be three orders of magnitude larger than the
        // elapsed time in seconds could ever be on a test device.
        assertTrue("uptime must be positive", sample.uptimeSeconds > 0)
        assertTrue(
            "uptime must be seconds, not milliseconds (got ${sample.uptimeSeconds})",
            sample.uptimeSeconds < 10_000_000,
        )
    }

    @Test
    fun thePlatformSourceReportsAPlausibleBatteryTemperature() {
        val sample = DeviceTelemetrySource(context).sample()

        // 0.0 is the documented "the platform will not say" value; when it does say,
        // it must be a plausible battery temperature, not a raw tenths-of-a-degree
        // reading (which would be ~300) or a nonsense value.
        assertTrue(
            "thermalTemp must be 0 or a plausible Celsius reading, got ${sample.thermalTemp}",
            sample.thermalTemp == 0.0 || (sample.thermalTemp in -20.0..120.0),
        )
    }

    @Test
    fun theRealReadingBecomesAWireCorrectFrame() {
        val frames = mutableListOf<String>()
        val reporter =
            TelemetryReporter(
                source = DeviceTelemetrySource(context),
                send = { frame ->
                    frames += frame

                    true
                },
                deviceId = "instrumented-device",
            )

        reporter.reportOnce()

        val frame = parse(frames.single())
        assertEquals("telemetry", frame.string("type"))
        assertEquals("instrumented-device", frame.string("deviceId"))
        assertTrue("uptime must be on the wire", frame.number("uptime") > 0)
    }
}
