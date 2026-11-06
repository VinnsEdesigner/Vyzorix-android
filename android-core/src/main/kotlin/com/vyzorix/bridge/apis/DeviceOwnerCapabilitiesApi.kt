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
 * What the device-owner privileges actually permit, and whether they are held.
 *
 * The companion to `device_owner`: that one says *whether* the app is privileged,
 * this one says *what that buys*. The distinction matters because the useful
 * capabilities are granted per-policy and not all of them come with the owner role —
 * a profile owner can wipe the profile but not lock the device, and a device owner
 * can lock it but a normal app cannot do either.
 *
 * Every field is a `DevicePolicyManager` call that needs no permission, because
 * asking whether a policy is available is not the same as applying it.
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
        val policy =
            call.context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no device policy service")

        val owner = policy.isDeviceOwnerApp(call.context.packageName)

        // Every call below is API 21 or lower and minSdk is 26, so no version guard
        // is needed — adding one would be dead code the dead-code gate rejects.
        //
        // `null` as the admin means "the policies set for this app", which is the only
        // reading available without a ComponentName. `isCameraDisabled` is the
        // exception: it takes a non-null admin, so it is answered from the owner flag
        // rather than guessed at.
        return ApiOutcome.ok(
            JSONObject()
                .put("deviceOwner", owner)
                // Locking the device is the one capability that is *only* available to
                // a device owner, which is why it is reported separately rather than
                // folded into the list of policies.
                .put("canLockNow", owner)
                .put("screenCaptureDisabled", policy.getScreenCaptureDisabled(null))
                .put("autoTimeRequired", policy.getAutoTimeRequired())
                .put("keyguardDisabledFeatures", policy.getKeyguardDisabledFeatures(null))
                .put("passwordQuality", policy.getPasswordQuality(null)),
        )
    }
}
