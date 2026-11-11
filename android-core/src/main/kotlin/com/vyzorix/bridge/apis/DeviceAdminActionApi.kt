package com.vyzorix.bridge.apis

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.service.VyzorixDeviceAdminReceiver
import org.json.JSONObject

/**
 * Exercises the declared device-admin policies: lock, wipe, password rules, camera,
 * keyguard, and the global proxy.
 *
 * ## Why each action is guarded the way it is
 *
 * Every method here needs this app to be an **active device admin**. The API checks
 * that once and refuses with the reason otherwise, rather than letting the platform
 * throw `SecurityException` — the difference between "you are not an admin" and "the
 * call failed" matters to whoever is driving it.
 *
 * The destructive ones — `wipe`, `resetPassword` — are opt-in by an explicit `action`
 * string, never by a status read, so a plain query changes nothing.
 *
 * ## What each action does
 *
 *  - `lock` — `lockNow()`, which takes no admin (it acts on the calling admin).
 *  - `wipe` — `wipeData(FLAG_WIPE_ALL)`, a factory reset. Irreversible.
 *  - `setPassword` — `resetPassword(password, 0)`.
 *  - `setPasswordQuality` / `setPasswordMinimumLength` / `setMaximumFailedPasswordsForWipe`
 *    — the `limit-password` policy's rules.
 *  - `setPasswordExpirationTimeout` — the `expire-password` policy.
 *  - `setCameraDisabled` — the `disable-camera` policy.
 *  - `setKeyguardDisabledFeatures` — the `disable-keyguard-features` policy.
 *  - `setGlobalProxy` — the `set-global-proxy` policy.
 *
 * ## The status read
 *
 * With no `action`, it reports whether this app is an active admin and every policy
 * value the `device_owner_capabilities` API does not already cover here: the failed
 * password count and whether the app can exercise the admin-gated methods.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PRIVATE,
        ApiTags.OP_WRITE,
        ApiTags.PROTECTION_SIGNATURE,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object DeviceAdminActionApi : ApiHandler {

    const val NAME = "device_admin_action"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val policy =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no device policy service")

        val admin = ComponentName(context, VyzorixDeviceAdminReceiver::class.java)
        val active = policy.isAdminActive(admin)

        val action = call.args["action"].orEmpty()

        if (action.isEmpty()) {
            return ApiOutcome.ok(status(policy, admin, active))
        }

        if (!active) {
            return ApiOutcome.fail(
                BridgeErrorKind.FORBIDDEN,
                "this app is not an active device admin; admin actions need the role",
            )
        }

        return try {
            perform(policy, admin, action, call.args["value"].orEmpty())
            ApiOutcome.ok(status(policy, admin, active).put("action", action).put("ok", true))
        } catch (e: SecurityException) {
            // The method exists and the app is an admin, but the platform refused —
            // some of these need device *owner*, not just admin. Reported with the
            // reason so the tier boundary is visible.
            ApiOutcome.ok(
                status(policy, admin, active)
                    .put("action", action)
                    .put("ok", false)
                    .put("reason", "the platform refused: ${e.message}"),
            )
        } catch (e: Exception) {
            ApiOutcome.fail(BridgeErrorKind.INVALID_ARGS, "admin action failed: ${e.message}")
        }
    }

    /** Dispatches one action. */
    private fun perform(
        policy: DevicePolicyManager,
        admin: ComponentName,
        action: String,
        value: String,
    ) {
        when (action) {
            "lock" -> policy.lockNow()
            "wipe" -> policy.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE)
            "setPassword" -> policy.resetPassword(value, 0)
            "setPasswordQuality" -> policy.setPasswordQuality(admin, value.toIntOrNull() ?: 0)
            "setPasswordMinimumLength" ->
                policy.setPasswordMinimumLength(admin, value.toIntOrNull() ?: 0)
            "setMaximumFailedPasswordsForWipe" ->
                policy.setMaximumFailedPasswordsForWipe(admin, value.toIntOrNull() ?: 0)
            "setPasswordExpirationTimeout" ->
                policy.setPasswordExpirationTimeout(admin, value.toLongOrNull() ?: 0L)
            "setCameraDisabled" -> policy.setCameraDisabled(admin, value.toBoolean())
            "setKeyguardDisabledFeatures" ->
                policy.setKeyguardDisabledFeatures(admin, value.toIntOrNull() ?: 0)
            "setGlobalProxy" -> setProxy(policy, admin, value)
            else -> throw IllegalArgumentException("unknown admin action: $action")
        }
    }

    /**
     * Sets the global HTTP proxy from a `host:port` value.
     *
     * `setGlobalProxy` (the *enforced* form) is `@SystemApi`; the public method is
     * `setRecommendedGlobalProxy`, which applies the proxy as a recommendation. That is
     * the honest ceiling for a normal app, and it is what the `set-global-proxy` policy
     * tag gates. An empty value clears it with a direct proxy to an empty host.
     */
    private fun setProxy(
        policy: DevicePolicyManager,
        admin: ComponentName,
        value: String,
    ) {
        val host = value.substringBefore(':').ifEmpty { null }
        val port = value.substringAfter(':', "").toIntOrNull() ?: 0

        val proxy =
            if (host == null) {
                // Clear: a direct proxy with no host means "no proxy".
                android.net.ProxyInfo.buildDirectProxy("", 0)
            } else {
                android.net.ProxyInfo.buildDirectProxy(host, port)
            }

        policy.setRecommendedGlobalProxy(admin, proxy)
    }

    /**
     * The current admin state and the policy values.
     *
     * The policy getters need an active admin — calling them without one throws
     * `SecurityException` — so they are read only when [active], and reported as
     * absent otherwise. That keeps a plain status read on a non-admin device an
     * answer ("not an admin") rather than an error.
     */
    private fun status(
        policy: DevicePolicyManager,
        admin: ComponentName,
        active: Boolean,
    ): JSONObject {
        val result =
            JSONObject()
                .put("isActiveAdmin", active)
                .put("isDeviceOwner", policy.isDeviceOwnerApp(admin.packageName))

        if (!active) {
            return result
        }

        return result
            .put("passwordQuality", policy.getPasswordQuality(admin))
            .put("passwordMinimumLength", policy.getPasswordMinimumLength(admin))
            .put("maximumFailedPasswordsForWipe", policy.getMaximumFailedPasswordsForWipe(admin))
            .put("passwordExpirationTimeout", policy.getPasswordExpirationTimeout(admin))
            .put("currentFailedPasswordAttempts", policy.currentFailedPasswordAttempts)
            .put("cameraDisabled", policy.getCameraDisabled(admin))
            .put("keyguardDisabledFeatures", policy.getKeyguardDisabledFeatures(admin))
            .put("maximumTimeToLock", policy.getMaximumTimeToLock(admin))
    }
}
