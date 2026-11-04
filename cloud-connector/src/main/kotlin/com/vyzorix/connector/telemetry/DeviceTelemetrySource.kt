package com.vyzorix.connector.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock

/**
 * Reads the device's telemetry from the platform.
 *
 * Only facts the app can read without a privileged permission, and only ones that
 * mean something to the operator: uptime and thermal state for device health. The
 * audio fields are honest zeros for now, because the audio engine does not exist
 * yet (Layer 2 is not in the repo); a dashboard that shows 0 knows the device is
 * reporting but has no engine, whereas a fabricated number would hide that.
 *
 * Thermal state is the reading that matters on the fleet's target hardware (the C22
 * throttles under sustained load), and `BatteryManager` reports it without a
 * permission. It is a battery temperature, not a CPU one — Android exposes no
 * unprivileged CPU sensor — so it is documented as such rather than presented as
 * something it is not.
 */
class DeviceTelemetrySource(
    private val context: Context,
) : TelemetryReporter.TelemetrySource {
    override fun sample(): TelemetryReporter.Sample =
        TelemetryReporter.Sample(
            uptimeMs = SystemClock.elapsedRealtime(),
            thermalTemp = batteryTemperatureC(),
            riskScore = 0,
            audioMode = 0,
            bufferLevel = 0,
            speakerOn = false,
        )

    /** Battery temperature in Celsius, or 0 when the platform will not say. */
    private fun batteryTemperatureC(): Double {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0.0

        // EXTRA_TEMPERATURE is tenths of a degree Celsius.
        val tenths = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)

        return tenths / TENTHS_PER_DEGREE
    }

    private companion object {
        const val TENTHS_PER_DEGREE = 10.0
    }
}
