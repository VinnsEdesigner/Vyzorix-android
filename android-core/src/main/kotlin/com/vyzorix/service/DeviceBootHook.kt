package com.vyzorix.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the service at boot.
 *
 * Many OEM builds block autostart until the app has been launched once, so this
 * is a convenience rather than a guarantee: onboarding must tell the user to open
 * the app once.
 */
class DeviceBootHook : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "boot completed; starting service")

        runCatching { VyzorixForegroundService.start(context) }
            .onFailure { Log.w(TAG, "could not start service at boot", it) }
    }

    private companion object {
        const val TAG = "DeviceBootHook"
    }
}
