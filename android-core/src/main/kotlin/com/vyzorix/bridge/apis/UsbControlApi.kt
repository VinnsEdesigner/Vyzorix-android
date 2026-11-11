package com.vyzorix.bridge.apis

import android.content.Context
import android.os.UserManager
import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.service.DeviceSettingsWriter
import org.json.JSONObject

/**
 * USB settings: whether USB debugging and file transfer are available, whether a
 * policy blocks them, and enabling them when this app has the privilege to.
 *
 * ## What can and cannot be changed
 *
 *  - **USB debugging** (`adb_enabled`) is a `Settings.Global` key. A device owner can
 *    set it with `setGlobalSetting`; so can a `WRITE_SECURE_SETTINGS` holder. A plain
 *    app cannot, and reports so.
 *  - **USB file transfer** is gated by the `DISALLOW_USB_FILE_TRANSFER` user
 *    restriction, which is a device-owner policy. Clearing it is a
 *    `DevicePolicyManager.clearUserRestriction` call, so this API can re-enable file
 *    transfer when the app is the owner and a policy had disabled it.
 *
 * ## Blocked?
 *
 * `usbFileTransferBlocked` and `debuggingBlockedByPolicy` report the two restrictions
 * that bar the two halves of USB use, so "USB does not work" is attributable to a
 * policy rather than left as a mystery.
 *
 * Read-only unless `enable=true` is passed, in the same shape as `developer_options`.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_HARDWARE,
        ApiTags.OP_WRITE,
        ApiTags.PROTECTION_SIGNATURE,
        ApiTags.VISIBILITY_PRIVATE,
        ApiTags.HW_USB,
    ],
)
object UsbControlApi : ApiHandler {

    const val NAME = "usb_control"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val users = context.getSystemService(Context.USER_SERVICE) as? UserManager

        val fileTransferBlocked =
            users?.userRestrictions?.getBoolean(UserManager.DISALLOW_USB_FILE_TRANSFER, false) ==
                true
        val debuggingBlocked =
            users?.userRestrictions?.getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES, false) ==
                true

        val result =
            JSONObject()
                .put("usbDebuggingEnabled", isGlobalOn(context, KEY_ADB))
                .put("usbFileTransferBlocked", fileTransferBlocked)
                .put("debuggingBlockedByPolicy", debuggingBlocked)
                .put("canWriteSettings", DeviceSettingsWriter.canWriteGlobal(context))
                .put("isDeviceOwner", DeviceSettingsWriter.isDeviceOwner(context))

        if (call.args["enable"]?.toBoolean() != true) {
            return ApiOutcome.ok(result)
        }

        // Enabling USB debugging: the global key, when the privilege is held.
        result.put(
            "usbDebuggingEnabledNow",
            !debuggingBlocked && DeviceSettingsWriter.setGlobal(context, KEY_ADB, "1"),
        )

        // Re-enabling file transfer: clear the device-owner restriction, which only the
        // owner can do. A non-owner reports false.
        result.put("usbFileTransferUnblocked", unblockFileTransfer(context, fileTransferBlocked))

        return ApiOutcome.ok(result)
    }

    /**
     * Clears `DISALLOW_USB_FILE_TRANSFER` when this app is the device owner.
     *
     * Returns true only when the restriction is no longer set afterwards. A non-owner
     * cannot clear it, so it reports false rather than pretending.
     */
    private fun unblockFileTransfer(
        context: Context,
        currentlyBlocked: Boolean,
    ): Boolean {
        if (!currentlyBlocked) return true
        if (!DeviceSettingsWriter.isDeviceOwner(context)) return false

        val policy =
            context.getSystemService(
                Context.DEVICE_POLICY_SERVICE,
            ) as? android.app.admin.DevicePolicyManager
                ?: return false

        return try {
            policy.clearUserRestriction(
                DeviceSettingsWriter.adminComponent(context),
                UserManager.DISALLOW_USB_FILE_TRANSFER,
            )

            true
        } catch (e: Exception) {
            // The platform refused the clear (a role that changed under us). Logged and
            // reported as "still blocked", never thrown.
            android.util.Log.w(TAG, "could not clear USB file-transfer block: ${e.message}")

            false
        }
    }

    private fun isGlobalOn(
        context: Context,
        key: String,
    ): Boolean = Settings.Global.getInt(context.contentResolver, key, 0) != 0

    private const val TAG = "UsbControl"
    private const val KEY_ADB = "adb_enabled"
}
