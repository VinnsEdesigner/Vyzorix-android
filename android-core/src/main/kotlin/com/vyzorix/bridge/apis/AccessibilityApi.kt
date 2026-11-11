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
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accessibility state, in full: which services are enabled, which are installed, and
 * whether accessibility is on at all.
 *
 * ## Why more than the enabled list
 *
 * An operator driving the screen needs to know whether a screen reader is
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
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
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

        return ApiOutcome.ok(
            JSONObject()
                .put("enabled", JSONArray(enabled))
                .put("count", enabled.size)
                .put("enabledComponents", enabledJson)
                .put("isEnabled", manager?.isEnabled ?: false)
                .put("isTouchExplorationEnabled", manager?.isTouchExplorationEnabled ?: false)
                .put("installedCount", installedJson.length())
                .put("installed", installedJson),
        )
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
