package com.vyzorix.bridge.apis

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * The device-administration picture, in full: whether this app is a device or profile
 * owner, and every admin (and owner) component active on the device.
 *
 * ## Why the full component list, not just the packages
 *
 * A device admin is a *component* (`pkg/.AdminReceiver`), and a package can register
 * more than one. Reporting only package names loses the receiver, which is the thing
 * an operator needs to disable it, to look up its policies, or to tell two admins of
 * the same package apart. So each admin is reported as its flattened `ComponentName`,
 * with the package and class split out alongside.
 *
 * The device owner and profile owner are also resolved to their components, so an
 * operator can see *which* app holds the owner role (it is not always the obvious
 * one). Both need no permission to read.
 *
 * ## The owner components
 *
 * `getDeviceOwnerComponentOnAnyUser` and `getProfileOwner` are `@SystemApi` — not in
 * the public SDK — so they are read by guarded reflection. On a device where the
 * method is absent (an OEM build that stripped it) the component is reported as empty
 * and the booleans above still answer the question. Reflection is the only route: the
 * public SDK has no owner-component accessor.
 *
 * ## What it deliberately does not report
 *
 * No policy values and no per-admin policy details: this answers "who administers
 * this device and is this app the owner", which is the question an operator has. The
 * policies themselves are `device_owner_capabilities`.
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

        val deviceOwner = policy.isDeviceOwnerApp(context.packageName)
        val profileOwner = policy.isProfileOwnerApp(context.packageName)
        val admins = policy.activeAdmins

        // Every admin as a full component, plus its parts. `getActiveAdmins` returns
        // null when the caller may not enumerate them (reported as adminsVisible=false,
        // never as "none").
        val adminArray = JSONArray()
        for (admin in admins.orEmpty()) {
            adminArray.put(
                componentJson(admin.flattenToString(), admin.packageName, admin.className),
            )
        }

        val deviceOwnerComponent = systemComponent(policy, "getDeviceOwnerComponentOnAnyUser")
        val profileOwnerComponent = systemComponent(policy, "getProfileOwner")

        return ApiOutcome.ok(
            JSONObject()
                .put("deviceOwner", deviceOwner)
                .put("profileOwner", profileOwner)
                .put("privileged", deviceOwner || profileOwner)
                .put("adminsVisible", admins != null)
                .put("adminCount", admins?.size ?: 0)
                .put("admins", adminArray)
                // The owner components, resolved from the system-only accessors. Empty
                // when there is no owner or the method is not present on this build.
                .put("deviceOwnerComponent", deviceOwnerComponent)
                .put("profileOwnerComponent", profileOwnerComponent)
                // The lock-screen info a device owner set. Public API, and non-empty
                // only when an owner configured it.
                .put(
                    "deviceOwnerLockScreenInfo",
                    policy.deviceOwnerLockScreenInfo?.toString().orEmpty(),
                ),
        )
    }

    /** A component as its flattened form plus its parts. */
    private fun componentJson(
        flattened: String,
        pkg: String,
        cls: String,
    ): JSONObject =
        JSONObject()
            .put("component", flattened)
            .put("packageName", pkg)
            .put("className", cls)

    /**
     * Reads an owner-component accessor that is `@SystemApi` on the platform.
     *
     * The accessor is hidden, so it is not on the public interface: `getMethod` does
     * not see it, and it must be looked up with `getDeclaredMethod` on the concrete
     * class and made accessible. On a build that stripped it entirely, or where the
     * reflective call is refused, the component is "" and the booleans above still
     * answer the question.
     */
    private fun systemComponent(
        policy: DevicePolicyManager,
        method: String,
    ): String =
        try {
            val declared = DevicePolicyManager::class.java.getDeclaredMethod(method)
            declared.isAccessible = true
            val component = declared.invoke(policy) as? ComponentName

            component?.flattenToString().orEmpty()
        } catch (e: Throwable) {
            // Absent on this build, or refused (the accessor is @SystemApi and may
            // throw for a caller without the system privilege). Reported as "no
            // component" — the booleans still answer the question — and logged with the
            // underlying cause, because an InvocationTargetException's own message is
            // null and hides why.
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            val why = "${cause.javaClass.simpleName}: ${cause.message}"
            Log.d(TAG, "owner component $method unavailable: $why")

            ""
        }

    private const val TAG = "DeviceOwner"
}
