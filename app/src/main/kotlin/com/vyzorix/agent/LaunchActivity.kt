package com.vyzorix.agent

import android.app.Activity
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

        VyzorixForegroundService.start(this)

        setContentView(TextView(this).apply { text = "Vyzorix device agent is running." })
    }
}
