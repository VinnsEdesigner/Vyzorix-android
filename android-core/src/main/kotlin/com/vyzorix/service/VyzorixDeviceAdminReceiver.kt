package com.vyzorix.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The device-admin receiver: what makes this app *eligible* to be added as a device
 * administrator.
 *
 * ## What this is, and what it is not
 *
 * A device administrator is a role the **user** grants, from Settings, after the app
 * declares a receiver with the `BIND_DEVICE_ADMIN` permission and a policy file. It is
 * not the same as device *owner*: an admin can act on the device (lock it, wipe it,
 * reset the password, disable the camera — the policies declared in
 * `res/xml/device_admin.xml`), but only an owner can set organisation-wide policy. The
 * app cannot grant itself either role; both are user actions, which is the point.
 *
 * ## Why the receiver lives in this module
 *
 * It lives here, not in the app, because the admin *state* is read and offered by the
 * bridge API (`device_admin_request`) and the notification, both of which are in this
 * module. The receiver itself is trivial — the enable/disable hooks — and the app
 * only contributes the manifest entry that names it.
 *
 * ## The callbacks are intentionally minimal
 *
 * The base class requires the enable/disable hooks; they log so the state change is
 * visible in the device log, and `onDisableRequested` returns a plain warning. Nothing
 * is blocked or hidden — a user can always remove the admin from Settings, which
 * is the escape hatch that keeps this safe.
 */
class VyzorixDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(
        context: Context,
        intent: Intent,
    ) {
        super.onEnabled(context, intent)
        Log.i(TAG, "device admin enabled")
    }

    override fun onDisabled(
        context: Context,
        intent: Intent,
    ) {
        super.onDisabled(context, intent)
        Log.i(TAG, "device admin disabled")
    }

    override fun onDisableRequested(
        context: Context,
        intent: Intent,
    ): CharSequence = "Removing administrator access turns off the device controls Vyzorix can use."

    private companion object {
        const val TAG = "VyzorixDeviceAdmin"
    }
}
