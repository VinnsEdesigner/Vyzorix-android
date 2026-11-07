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

        requestRuntimePermissions()
        VyzorixForegroundService.start(this)

        setContentView(TextView(this).apply { text = "Vyzorix device agent is running." })
    }

    /**
     * Asks for the runtime permissions the agent needs, in one prompt.
     *
     * POST_NOTIFICATIONS (API 33+): needed to show the pairing code. A device that
     * refuses it still enrolls and works; the code is simply not visible, which is
     * the user's choice to make.
     *
     * READ/WRITE_EXTERNAL_STORAGE: needed so the container can write to shared
     * storage. Scoped storage gates the shared tree on holding the permission even
     * at targetSdk 28, so without it the tree is read-only on API 29+ (see
     * doc/DEVICE_FILESYSTEM_ACCESS.md).
     */
    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        // WRITE_EXTERNAL_STORAGE is a runtime permission only through API 29; from
        // API 30 it is granted implicitly and has no effect, so it is not requested.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.Q) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        val missing =
            needed.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isEmpty()) return

        requestPermissions(missing.toTypedArray(), RUNTIME_REQUEST_CODE)
    }

    private companion object {
        const val RUNTIME_REQUEST_CODE = 1
    }
}
