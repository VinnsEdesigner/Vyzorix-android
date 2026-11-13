package com.vyzorix.connector.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.vyzorix.connector.deploy.InstalledState
import com.vyzorix.connector.deploy.InstalledStateStore
import com.vyzorix.connector.deploy.InstalledStateTarget

/**
 * Reads the device's telemetry from the platform.
 *
 * Only facts the app can read without a privileged permission, and only ones that
 * mean something to the operator: uptime and thermal state for device health.
 *
 * The `audioMode`, `bufferLevel` and `speakerOn` fields are reported as zeros
 * because **this device has no audio engine** — the design is a headless Debian
 * agent, not an audio router, and nothing in this repo produces those readings. The
 * server's telemetry schema carries the fields, so they are sent rather than omitted;
 * a dashboard that shows 0 can tell "no audio engine on this device" from "device not
 * reporting", whereas a fabricated number would hide which.
 *
 * Uptime is in **seconds**, not milliseconds: the server's `TelemetryFrame.uptime`
 * and its `uptime` column are seconds, so a millisecond reading would make every
 * device look as if it had been up for decades.
 *
 * Thermal state is the reading that matters most on hardware that throttles
 * throttles under sustained load), and `BatteryManager` reports it without a
 * permission. It is a battery temperature, not a CPU one — Android exposes no
 * unprivileged CPU sensor — so it is documented as such rather than presented as
 * something it is not.
 */
class DeviceTelemetrySource(
    private val context: Context,
    /**
     * Reads what the device is actually running, so the heartbeat can report it.
     *
     * Null disables the report (a build with no app storage): the frame then carries
     * zeros, which the server reads as "this device does not report state" rather
     * than as drift.
     */
    private val installedState: InstalledStateTarget? = null,
) : TelemetryReporter.TelemetrySource {
    override fun sample(): TelemetryReporter.Sample {
        // Read once per report, not per field: the file is written by another thread
        // (the apply thread) and a report must see one consistent state, not half of
        // one before a write and half after.
        val installed = installedState?.read() ?: InstalledState()

        return TelemetryReporter.Sample(
            uptimeSeconds = SystemClock.elapsedRealtime() / MILLIS_PER_SECOND,
            thermalTemp = batteryTemperatureC(),
            riskScore = 0,
            audioMode = 0,
            bufferLevel = 0,
            speakerOn = false,
            configVersion = installed.version,
            appDigests =
                installed.apps.mapValues { (_, artifacts) ->
                    InstalledStateStore.digest(artifacts).orEmpty()
                },
        )
    }

    /** Battery temperature in Celsius, or 0 when the platform will not say. */
    private fun batteryTemperatureC(): Double {
        val status =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return 0.0

        // EXTRA_TEMPERATURE is tenths of a degree Celsius.
        val tenths = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)

        return tenths / TENTHS_PER_DEGREE
    }

    private companion object {
        const val TENTHS_PER_DEGREE = 10.0
        const val MILLIS_PER_SECOND = 1000L

        /** Must match the applier's app-storage directory. */
        const val APPS_DIR = "apps"
    }
}
