package com.vyzorix.bridge.apis

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Whether this app is a device owner or profile owner, and who administers the
 * device if anything does.
 *
 * ## Why this matters before anything else is attempted
 *
 * A device owner can set the screen timeout, silence the volume, disable the camera
 * and keep the app alive against battery optimisation. A normal app cannot, and the
 * failures look identical from the outside: a call that is refused and a call that
 * silently does nothing. Reporting the privilege level first is what lets an operator
 * know which half of the API surface is even available.
 *
 * ## What it deliberately does not report
 *
 * No administrator component names beyond the packages that are active, and no
 * policy values. This answers "what is this app allowed to do" and "is anyone else
 * administrating this device", which is the question an operator actually has.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object DeviceOwnerApi : ApiHandler {

    const val NAME = "device_owner"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val policy =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no device policy service")

        // Both of these need no permission: an app may always ask whether *it* is
        // privileged, and an app targeting API 28 may still enumerate the active
        // admins. `getActiveAdmins` returns null when it may not, which is reported
        // as "not visible" rather than as "none".
        val deviceOwner = policy.isDeviceOwnerApp(context.packageName)
        val profileOwner = policy.isProfileOwnerApp(context.packageName)
        val admins = policy.activeAdmins

        return ApiOutcome.ok(
            JSONObject()
                .put("deviceOwner", deviceOwner)
                .put("profileOwner", profileOwner)
                .put("privileged", deviceOwner || profileOwner)
                .put("adminsVisible", admins != null)
                .put("adminCount", admins?.size ?: 0)
                .put(
                    "admins",
                    org.json.JSONArray(
                        admins.orEmpty().map { it.packageName },
                    ),
                ),
        )
    }
}
