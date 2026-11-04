package com.vyzorix.agent

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import com.vyzorix.service.VyzorixForegroundService

/**
 * The launcher, and nothing else.
 *
 * A headless agent still needs a way to be started on a fresh install. Many OEM
 * builds block `BOOT_COMPLETED` autostart until the app has been launched once
 * (see DeviceBootHook), so without an entry point the service would never run on
 * a device that has just been sideloaded. Opening this activity starts the
 * service; the activity then has no further role.
 *
 * There is deliberately no UI beyond a status line: the device is driven over the
 * bridge socket, not on screen.
 */
class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        VyzorixForegroundService.start(this)

        setContentView(TextView(this).apply { text = "Vyzorix device agent is running." })
    }

    /**
     * Asks for POST_NOTIFICATIONS on API 33+, where it became a runtime permission.
     *
     * It is needed for exactly one thing: showing the pairing code. A device that
     * refuses it still enrolls and still works — the code is simply not visible, and
     * the operator cannot claim it, which is the user's choice to make.
     *
     * Below API 33 the permission is granted at install, so there is nothing to ask.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted =
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) return

        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_REQUEST_CODE,
        )
    }

    private companion object {
        const val NOTIFICATION_REQUEST_CODE = 1
    }
}
