package com.vyzorix.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * The launcher is a one-time setup hop, not a screen.
 *
 * ## What it does
 *
 * A freshly sideloaded app has two things to grant: the runtime permissions it needs
 * (POST_NOTIFICATIONS on API 33+, and the storage permissions the container uses) and
 * the accessibility service. Neither can be granted by the app itself. Tapping the
 * launcher icon therefore asks for the runtime permissions, then opens the
 * Accessibility settings screen — and nothing else. This activity shows no UI of its
 * own, starts no container, and finishes immediately.
 *
 * ## Why the app is un-launchable
 *
 * The agent is headless: it is driven over the bridge socket and the cloud, never on
 * screen. There is no app UI to open, and opening one would be the wrong shape — a
 * device that must be "used" like an app is not an agent. So the launcher never shows
 * content, and the notification
 * ([com.vyzorix.service.DeviceNotification]) deliberately carries no content intent:
 * there is nothing to open. The only interaction the agent offers is the "Start the
 * Vyzorix" action, which starts the *service*.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty()) {
            // Stay for the dialog; onRequestPermissionsResult continues the hop.
            requestPermissions(missing.toTypedArray(), RUNTIME_REQUEST_CODE)

            return
        }

        openAccessibilitySettingsAndFinish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != RUNTIME_REQUEST_CODE) return

        // Whether or not they were granted, continue: a refused POST_NOTIFICATIONS
        // means no notification on API 33+, which the operator can fix later, and the
        // accessibility hop is still the point of this activity.
        openAccessibilitySettingsAndFinish()
    }

    private fun openAccessibilitySettingsAndFinish() {
        val opened =
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )

                true
            }.getOrElse {
                Log.w(TAG, "could not open the accessibility settings", it)

                false
            }

        Log.i(TAG, "launcher opened accessibility settings=$opened; finishing")

        // No UI, and nothing to come back to: finish before a window is drawn. The
        // operator now flips the switch, and the persistent notification takes over.
        finish()
    }

    /**
     * The runtime permissions the agent needs, minus any already granted.
     *
     * POST_NOTIFICATIONS (API 33+): needed to show the notification at all.
     * READ/WRITE_EXTERNAL_STORAGE: the container's view of shared storage; scoped
     * storage gates the shared tree on the grant even at targetSdk 28.
     * BLUETOOTH_CONNECT (API 31+): `BluetoothAdapter.isEnabled` needs it.
     */
    private fun missingRuntimePermissions(): List<String> {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.Q) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        return needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private companion object {
        const val TAG = "LaunchActivity"
        const val RUNTIME_REQUEST_CODE = 1
    }
}
