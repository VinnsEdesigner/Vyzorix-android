package com.vyzorix.bridge.apis

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.service.VyzorixDeviceAdminReceiver
import org.json.JSONObject

/**
 * Opens the system screen that adds this app as a device administrator, and reports
 * whether it is one already.
 *
 * ## Why an API for this
 *
 * Adding a device admin is a user action on a Settings screen; an app cannot grant
 * itself the role. On a headless device the operator is remote, so the useful thing
 * this API can do is *bring the screen up* — a `ACTION_ADD_DEVICE_ADMIN` intent with
 * this app's receiver — so whoever has the device in hand confirms it. That is the
 * same shape as the accessibility hop in the launcher: the agent opens the door, the
 * person walks through it.
 *
 * The result says whether the admin is already active, so a caller can tell "already
 * granted" from "screen opened, waiting for a human".
 *
 * ## Why it is a write
 *
 * It starts an Activity, which changes what the user sees, so it is tagged `op:write`
 * rather than `op:read` even though it grants nothing itself.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_WRITE,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object DeviceAdminRequestApi : ApiHandler {

    const val NAME = "device_admin_request"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val policy =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no device policy service")

        val component = adminComponent(context)
        val active = policy.isAdminActive(component)

        if (active) {
            return ApiOutcome.ok(
                JSONObject()
                    .put("active", true)
                    .put("opened", false)
                    .put("component", component.flattenToString()),
            )
        }

        // ACTION_ADD_DEVICE_ADMIN shows the "Activate device admin app?" screen, which
        // lists the policies from res/xml/device_admin.xml. The user confirms; the app
        // cannot.
        val intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val opened =
            try {
                context.startActivity(intent)

                true
            } catch (e: Exception) {
                // A device with no device-admin settings screen. Not fatal: the caller
                // learns the screen could not be opened. Logged so the reason is visible.
                android.util.Log.w(TAG, "could not open the device-admin screen", e)

                false
            }

        return ApiOutcome.ok(
            JSONObject()
                .put("active", false)
                .put("opened", opened)
                .put("component", component.flattenToString()),
        )
    }

    /** This app's admin receiver, the component the activation screen is for. */
    private fun adminComponent(context: Context): ComponentName =
        ComponentName(context.packageName, VyzorixDeviceAdminReceiver::class.java.name)

    private const val TAG = "DeviceAdminRequest"
}
