package com.vyzorix.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Writing device settings that need more than a normal app's privilege.
 *
 * Two powers can write a global setting, and which one is held decides how:
 *
 *  - **Device owner** — `DevicePolicyManager.setGlobalSetting` writes the key on the
 *    device owner's behalf, no `WRITE_SECURE_SETTINGS` needed. This is the strong
 *    route and the one a provisioned agent has.
 *  - **`WRITE_SECURE_SETTINGS`** — a signature permission. A normal app cannot hold
 *    it, but a platform-signed or device-owner build can, and then `Settings.Global`
 *    is writable directly.
 *
 * Everything here reports *whether* it could write rather than throwing, because the
 * interesting answer on an unprovisioned device is "no, and here is why" — the same
 * shape the rest of the bridge uses.
 */
object DeviceSettingsWriter {
    private const val TAG = "DeviceSettingsWriter"

    /** The admin component that represents this app. */
    fun adminComponent(context: Context): ComponentName =
        ComponentName(context.packageName, VyzorixDeviceAdminReceiver::class.java.name)

    /** True when this app is the device owner, so it may write global settings. */
    fun isDeviceOwner(context: Context): Boolean =
        policy(context)?.isDeviceOwnerApp(context.packageName) ?: false

    /**
     * True when a global setting may be written: this app is the device owner, or it
     * holds `WRITE_SECURE_SETTINGS`. The two are the only ways in.
     */
    fun canWriteGlobal(context: Context): Boolean =
        isDeviceOwner(context) || holdsWriteSecureSettings(context)

    /** True when this app holds `WRITE_SECURE_SETTINGS` (signature-level). */
    fun holdsWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Writes a `Settings.Global` key, by whichever route is available.
     *
     * Returns true on success. A device owner writes through `DevicePolicyManager`; a
     * `WRITE_SECURE_SETTINGS` holder writes `Settings.Global` directly. When neither
     * holds, this returns false without attempting — the caller reports the reason.
     *
     * `setGlobalSetting` refuses a small set of keys (the ones a policy must not let an
     * owner change, such as the device-provisioned flag); a refusal surfaces as an
     * exception and is returned as false.
     */
    fun setGlobal(
        context: Context,
        key: String,
        value: String,
    ): Boolean {
        // Device owner first: `setGlobalSetting` writes on the owner's behalf with no
        // permission. But it only honours a key on the platform's allowlist — for a key
        // it does not cover the call returns without throwing and changes nothing, so a
        // direct write is tried as a fallback when `WRITE_SECURE_SETTINGS` is held.
        if (isDeviceOwner(context)) {
            val policy = policy(context)
            if (policy != null) {
                try {
                    policy.setGlobalSetting(adminComponent(context), key, value)
                } catch (e: Exception) {
                    Log.w(TAG, "device owner could not write $key=$value: ${e.message}")
                }

                if (Settings.Global.getString(context.contentResolver, key) == value) {
                    return true
                }
            }
        }

        if (holdsWriteSecureSettings(context)) {
            return try {
                Settings.Global.putString(context.contentResolver, key, value)

                // Confirm, for the same reason as above: a write that reports success is
                // not a write that landed.
                Settings.Global.getString(context.contentResolver, key) == value
            } catch (e: Exception) {
                Log.w(TAG, "WRITE_SECURE_SETTINGS holder could not write $key=$value: ${e.message}")

                false
            }
        }

        Log.i(TAG, "no route to write $key (owner allowlist or WRITE_SECURE_SETTINGS)")

        return false
    }

    private fun policy(context: Context): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
}
