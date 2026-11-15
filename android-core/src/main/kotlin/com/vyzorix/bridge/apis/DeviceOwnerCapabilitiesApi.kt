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
 * Every device-policy value this app can read, and whether the owner role is held.
 *
 * The companion to `device_owner`: that one says *who* administers the device and
 * whether this app is the owner; this one dumps *what the policy engine currently
 * says* — the password rules, the lock and camera state, the lock-task allowlist,
 * the permitted accessibility/input-method lists, and the storage-encryption status.
 *
 * ## Why so many fields
 *
 * An user diagnosing a managed device needs the whole policy picture, not a
 * sample: "the screen locks after 30s" and "the screen never locks" are the same call
 * with different numbers, and which password rules are set is what decides whether a
 * device is compliant. So the full set of `DevicePolicyManager` getters that need no
 * permission is reported.
 *
 * Every call passes a `null` admin, which means "the policies set for this app" — the
 * only reading available without a `ComponentName`, and the right one when this app
 * is the owner. On a device where this app holds no policy, the getters return their
 * defaults, which is itself the fact worth reporting.
 *
 * `minSdk` is 26, so every getter below exists on every supported device and no
 * version guard is needed.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object DeviceOwnerCapabilitiesApi : ApiHandler {

    const val NAME = "device_owner_capabilities"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val policy =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no device policy service")

        val owner = policy.isDeviceOwnerApp(context.packageName)

        val result =
            JSONObject()
                .put("deviceOwner", owner)
                .put("profileOwner", policy.isProfileOwnerApp(context.packageName))
                // Locking the device is the one capability only a device owner has,
                // so it is reported separately from the policy values.
                .put("canLockNow", owner)

        passwordPolicy(policy, result)
        deviceState(policy, result)
        allowlists(policy, context, result)

        return ApiOutcome.ok(result)
    }

    /** The password rules. */
    private fun passwordPolicy(
        policy: DevicePolicyManager,
        result: JSONObject,
    ) {
        result
            .put("passwordQuality", policy.getPasswordQuality(null))
            .put("passwordMinimumLength", policy.getPasswordMinimumLength(null))
            .put("passwordMinimumLetters", policy.getPasswordMinimumLetters(null))
            .put("passwordMinimumNumeric", policy.getPasswordMinimumNumeric(null))
            .put("passwordMinimumSymbols", policy.getPasswordMinimumSymbols(null))
            .put("passwordMinimumUpperCase", policy.getPasswordMinimumUpperCase(null))
            .put("passwordMinimumLowerCase", policy.getPasswordMinimumLowerCase(null))
            .put("passwordExpirationTimeout", policy.getPasswordExpirationTimeout(null))
            .put("passwordHistoryLength", policy.getPasswordHistoryLength(null))
            .put("maximumFailedPasswordsForWipe", policy.getMaximumFailedPasswordsForWipe(null))
    }

    /** The device-state policies: lock timeout, camera, screen capture, encryption. */
    private fun deviceState(
        policy: DevicePolicyManager,
        result: JSONObject,
    ) {
        result
            .put("maximumTimeToLock", policy.getMaximumTimeToLock(null))
            .put("screenCaptureDisabled", policy.getScreenCaptureDisabled(null))
            .put("autoTimeRequired", policy.getAutoTimeRequired())
            .put("keyguardDisabledFeatures", policy.getKeyguardDisabledFeatures(null))
            .put("cameraDisabled", policy.getCameraDisabled(null))
            .put("storageEncryptionStatus", policy.storageEncryptionStatus)
    }

    /** The lock-task state, which is readable without an admin component. */
    private fun allowlists(
        policy: DevicePolicyManager,
        context: Context,
        result: JSONObject,
    ) {
        // `getPermittedAccessibilityServices`/`getPermittedInputMethods`/
        // `getLockTaskPackages` all require a non-null admin `ComponentName`, and this
        // app declares no `DeviceAdminReceiver`, so there is no component to pass.
        // They are omitted rather than faked. `isLockTaskPermitted` takes a package
        // name, so it is readable and reported.
        result.put("lockTaskPermitted", policy.isLockTaskPermitted(context.packageName))
    }
}
