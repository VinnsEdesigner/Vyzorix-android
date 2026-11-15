package com.vyzorix.bridge.apis

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.service.DeviceSettingsWriter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accessibility state, in full: which services are enabled, which are installed, and
 * whether accessibility is on at all.
 *
 * ## Why more than the enabled list
 *
 * An user driving the screen needs to know whether a screen reader is
 * intercepting input, and *which* services are present to do so. The enabled list
 * alone hides a service that is installed but off — the difference between "no
 * screen reader on this device" and "one is installed and could be turned on". So
 * both lists are reported, each service as its flattened component with its package
 * and class, plus the per-service flags that say what it can do.
 *
 * ## What needs no permission
 *
 * The enabled-services setting is world-readable. The installed-service list comes
 * from `AccessibilityManager`, which returns the services the system knows about
 * without a permission — the `BIND_ACCESSIBILITY_SERVICE` permission is for the
 * service to be bound, not for this read.
 *
 * ## Enabling and disabling a service, and why it needs the signature grant
 *
 * The enabled set lives in the `Settings.Secure` key `enabled_accessibility_services`.
 * Writing it needs `WRITE_SECURE_SETTINGS` — a signature permission a plain sideloaded
 * app cannot hold, and a device owner does **not** get it either (there is no
 * `setGlobalSetting` path for a secure key). So `action=enable`/`disable` are attempted
 * only when that grant is held; otherwise they report `changed:false` with the reason,
 * rather than pretending. On a stock device this means the action is a no-op and the
 * answer is "needs `adb shell pm grant … WRITE_SECURE_SETTINGS`".
 *
 * This is deliberate: it is the same wall as the overlay grant. Accessibility state and
 * app-ops are both outside what an unprivileged app can change, and this API says so
 * instead of offering a button that silently fails.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_WRITE,
        ApiTags.PROTECTION_SIGNATURE,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object AccessibilityApi : ApiHandler {

    const val NAME = "accessibility"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context

        val raw =
            Settings.Secure.getString(context.contentResolver, ENABLED_SERVICES).orEmpty()

        val enabled = raw.split(SEPARATOR).filter { it.isNotEmpty() }

        val manager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

        val enabledJson = JSONArray()
        for (service in enabled) {
            enabledJson.put(componentOf(service))
        }

        val installedJson = JSONArray()
        for (info in manager?.installedAccessibilityServiceList.orEmpty()) {
            installedJson.put(serviceInfoJson(context, info))
        }

        val result =
            JSONObject()
                .put("enabled", JSONArray(enabled))
                .put("count", enabled.size)
                .put("enabledComponents", enabledJson)
                .put("isEnabled", manager?.isEnabled ?: false)
                .put("isTouchExplorationEnabled", manager?.isTouchExplorationEnabled ?: false)
                .put("installedCount", installedJson.length())
                .put("installed", installedJson)
                // Whether this app could change the enabled set at all: only a
                // WRITE_SECURE_SETTINGS holder can. A device owner cannot (no secure path).
                .put("canWrite", DeviceSettingsWriter.canWriteSecure(context))

        val action = call.args["action"].orEmpty()
        if (action != "enable" && action != "disable") {
            return ApiOutcome.ok(result)
        }

        val component = call.args["component"].orEmpty()
        if (component.isEmpty()) {
            return ApiOutcome.fail(
                BridgeErrorKind.INVALID_ARGS,
                "component is required for $action (pkg/pkg.Class)",
            )
        }

        return ApiOutcome.ok(
            toggle(context, enabled, component, enable = action == "enable", result),
        )
    }

    /**
     * Adds or removes [component] from the enabled set.
     *
     * The whole set is rewritten (the setting is a single colon-separated string), and
     * the read-back is what decides `changed` — a write that reports success but does not
     * land reports false. When the signature grant is missing this reports false with the
     * reason without attempting.
     */
    private fun toggle(
        context: Context,
        current: List<String>,
        component: String,
        enable: Boolean,
        result: JSONObject,
    ): JSONObject {
        result.put("action", if (enable) "enable" else "disable").put("component", component)

        if (!DeviceSettingsWriter.canWriteSecure(context)) {
            return result
                .put("changed", false)
                .put(
                    "reason",
                    "writing enabled_accessibility_services needs WRITE_SECURE_SETTINGS " +
                        "(signature); this app does not hold it",
                )
        }

        val next = current.filter { it != component }.toMutableList()
        if (enable) next.add(component)

        val wrote =
            DeviceSettingsWriter.setSecure(context, ENABLED_SERVICES, next.joinToString(":"))

        return result
            .put("changed", wrote)
            .put("enabled", JSONArray(next))
            .put("count", next.size)
    }

    /** A flattened `pkg/class` string as its parts. */
    private fun componentOf(flattened: String): JSONObject {
        val slash = flattened.indexOf('/')
        val pkg = if (slash >= 0) flattened.substring(0, slash) else flattened
        val cls = if (slash >= 0) flattened.substring(slash + 1) else ""

        return JSONObject()
            .put("component", flattened)
            .put("packageName", pkg)
            .put("className", cls)
    }

    /** One installed service and what it declares it can do. */
    private fun serviceInfoJson(
        context: Context,
        info: AccessibilityServiceInfo,
    ): JSONObject {
        val resolve = info.resolveInfo
        val component =
            if (resolve?.serviceInfo != null) {
                ComponentName(
                    resolve.serviceInfo.packageName,
                    resolve.serviceInfo.name,
                ).flattenToString()
            } else {
                ""
            }

        return JSONObject()
            .put("component", component)
            .put("packageName", resolve?.serviceInfo?.packageName.orEmpty())
            .put("label", resolve?.loadLabel(context.packageManager)?.toString().orEmpty())
            .put("eventTypes", info.eventTypes)
            .put("feedbackType", info.feedbackType)
            .put(
                "canRetrieveWindowContent",
                info.getCapabilities() and CAPABILITY_RETRIEVE_WINDOW_CONTENT != 0,
            ).put("flags", info.flags)
            .put("notificationTimeout", info.notificationTimeout)
            .put("packageNames", JSONArray(info.packageNames?.toList().orEmpty()))
            .put(
                "isSystemApp",
                resolve?.serviceInfo?.applicationInfo?.let { isSystem(it.flags) } ?: false,
            )
    }

    private fun isSystem(flags: Int): Boolean = flags and ApplicationInfo.FLAG_SYSTEM != 0

    private const val ENABLED_SERVICES = "enabled_accessibility_services"
    private const val SEPARATOR = ':'

    /** `AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT`, API 18. */
    private const val CAPABILITY_RETRIEVE_WINDOW_CONTENT = 0x00000001
}
