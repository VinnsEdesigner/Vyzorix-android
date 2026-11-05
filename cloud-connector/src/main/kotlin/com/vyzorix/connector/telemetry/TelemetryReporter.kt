package com.vyzorix.connector.telemetry

import com.vyzorix.connector.cloud.CloudJson
import com.vyzorix.connector.cloud.TelemetryFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString

/**
 * Reports device telemetry on the cadence the config asks for.
 *
 * Telemetry is the device telling the server how it is: uptime, audio mode, buffer
 * level, thermal state. The server stores it and drives its threshold alerts from
 * it, so a device that never reports is invisible to the operator — the dashboard
 * shows a device that is online with no telemetry at all, which reads as broken
 * rather than as "not reporting".
 *
 * The interval comes from the config (`vvv hb 60s`), not a constant, so the server
 * can slow reporting across devices without shipping a new APK. [updateInterval]
 * takes effect on the next tick.
 *
 * The readings come from a [TelemetrySource] rather than being collected here, so
 * the cadence and the frame are testable without a device.
 */
class TelemetryReporter(
    private val source: TelemetrySource,
    private val send: (String) -> Boolean,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    initialIntervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
) {
    /** Supplies the device's current facts. Called once per report, on the timer thread. */
    fun interface TelemetrySource {
        fun sample(): Sample
    }

    /**
     * One telemetry reading. Mirrors the server's `TelemetryFrame`; every field but
     * uptime is optional there, so a device reports what it has.
     */
    data class Sample(
        val uptimeSeconds: Long = 0,
        val riskScore: Int = 0,
        val audioMode: Int = 0,
        val bufferLevel: Int = 0,
        val thermalTemp: Double = 0.0,
        val speakerOn: Boolean = false,
    )

    private val running = AtomicBoolean(false)

    @Volatile
    private var intervalSeconds: Int = initialIntervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS)

    private var thread: Thread? = null

    /**
     * Applies a new cadence. A value below [MIN_INTERVAL_SECONDS] is clamped: the
     * server owns the value, but a typo in a config must not turn every device into a
     * request flood.
     */
    fun updateInterval(seconds: Int) {
        intervalSeconds = seconds.coerceAtLeast(MIN_INTERVAL_SECONDS)
    }

    /** Starts reporting. Idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) return

        thread =
            Thread({ loop() }, "vyzorix-telemetry").apply {
                isDaemon = true
                start()
            }
    }

    /** Stops reporting. */
    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    /**
     * Sends one report now and returns whether the socket took it.
     *
     * Exposed so a caller can report immediately on connect rather than waiting a
     * full interval: a device that just came online should not be invisible for the
     * first minute.
     */
    fun reportOnce(): Boolean {
        val sample = source.sample()
        val frame =
            TelemetryFrame(
                timestamp = clock(),
                deviceId = deviceId,
                uptime = sample.uptimeSeconds,
                riskScore = sample.riskScore,
                audioMode = sample.audioMode,
                bufferLevel = sample.bufferLevel,
                thermalTemp = sample.thermalTemp,
                speakerOn = sample.speakerOn,
            )

        // Encoded with the outbound config so the `type` discriminator is always on
        // the wire: the server routes frames by it, and the shared inbound config
        // drops defaults.
        return send(CloudJson.outbound.encodeToString(frame))
    }

    private fun loop() {
        while (running.get()) {
            reportOnce()

            // Sleep in small slices so a stop or an interval change is noticed
            // promptly rather than after a full (possibly long) interval.
            var slept = 0L
            while (running.get() && slept < intervalSeconds * MILLIS_PER_SECOND) {
                try {
                    Thread.sleep(SLEEP_SLICE_MS)
                } catch (e: InterruptedException) {
                    // A stop is a request to end, not a failure.
                    Thread.currentThread().interrupt()

                    return
                }
                slept += SLEEP_SLICE_MS
            }
        }
    }

    companion object {
        /** Matches the server's default (`DefaultTelemetryIntervalSeconds`). */
        const val DEFAULT_INTERVAL_SECONDS = 60

        /** A floor on the cadence, so a bad config cannot turn into a flood. */
        const val MIN_INTERVAL_SECONDS = 5

        private const val MILLIS_PER_SECOND = 1000L
        private const val SLEEP_SLICE_MS = 250L
    }
}
