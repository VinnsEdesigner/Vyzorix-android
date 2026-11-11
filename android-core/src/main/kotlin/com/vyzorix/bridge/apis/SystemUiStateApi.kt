package com.vyzorix.bridge.apis

import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * The inputs SystemUI's visibility gates read — the state behind a hidden tile, a greyed
 * toggle, or a suppressed panel.
 *
 * ## Why this exists
 *
 * "Something is hidden in the UI but still works underneath" is a real and confusing
 * state: SystemUI hides or disables a control because a *setting* or a *policy* says so,
 * while the capability itself is untouched. SystemUI's own decision is not published
 * anywhere an app can read — it is made in SystemUI's process from inputs that **are**
 * readable. So this reports those inputs, which is the closest an app can get to "what is
 * SystemUI gating on", and it is honest about the boundary: it reads the same keys
 * SystemUI reads, it does not read SystemUI's mind.
 *
 * ## What is read
 *
 *  - **`Settings.Secure`** — the Quick Settings tile layout (`sysui_qs_tiles`, what the
 *    panel actually renders), the auto-added tiles, the accessibility/notification-listener
 *    registrations, `user_setup_complete`, `install_non_market_apps`, and the mock-location
 *    and first-use flags. These are readable by an app targeting API ≤33, which this app
 *    does (targetSdk 28), so the QS layout is genuinely visible here.
 *  - **`Settings.Global`** — `adb_enabled`, `development_settings_enabled`,
 *    `device_provisioned`, `stay_on_while_plugged_in`, and `policy_control` (the
 *    device-owner key that can suppress whole settings screens).
 *  - **User restrictions** — the active `no_*` set, which is what greys a tile out. Named
 *    in full so a hidden control is attributable to a policy, not a mystery.
 *
 * ## What it deliberately is not
 *
 * Read-only, and observation only. It reads the app's own view of settings; it does not
 * write them and it does not attempt to bypass SystemUI. Writing `Settings.Secure`/`Global`
 * is a privileged operation (device owner or `WRITE_SECURE_SETTINGS`) and doing it here
 * would be a UI-shaping side effect of a query.
 *
 * ## Why the keys are a fixed list
 *
 * The value is that a caller sees a key it did not know to ask for, so the keys are
 * enumerated with a plain-language meaning rather than passed in. `present` distinguishes
 * "the key is set" from "the key is absent", because an absent QS-tile key means SystemUI
 * is using its default layout, which is itself an answer.
 *
 * No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object SystemUiStateApi : ApiHandler {

    const val NAME = "systemui_state"

    /** One readable setting: its key, what it controls, and the namespace. */
    private class SettingKey(
        val namespace: String,
        val key: String,
        val meaning: String,
    )

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val resolver = context.contentResolver

        val secure = JSONArray()
        val global = JSONArray()
        for (entry in KEYS) {
            val value =
                runCatching {
                    if (entry.namespace == "secure") {
                        Settings.Secure.getString(resolver, entry.key)
                    } else {
                        Settings.Global.getString(resolver, entry.key)
                    }
                }.getOrNull()

            val json =
                JSONObject()
                    .put("key", entry.key)
                    .put("meaning", entry.meaning)
                    .put("present", value != null)
                    .put("value", value.orEmpty())

            if (entry.namespace == "secure") secure.put(json) else global.put(json)
        }

        // The QS tile layout parsed into a list, because that is the one SystemUI input an
        // operator reads directly: which tiles exist, and in what order.
        val tilesRaw = runCatching { Settings.Secure.getString(resolver, KEY_QS_TILES) }.getOrNull()

        val result =
            JSONObject()
                .put("secure", secure)
                .put("global", global)
                .put("qsTiles", parseTiles(tilesRaw))
                .put("qsTilesRaw", tilesRaw.orEmpty())
                // The policy inputs that grey a tile out. Full set, so a hidden control is
                // attributable to a named restriction.
                .put("activeRestrictions", activeRestrictions(context))
                .put("isDeviceOwner", isDeviceOwner(context))
                // Whether the well-known OEM/settings components exist and can be opened.
                // This is the read-only half of "am start -n <component>": an app cannot
                // run `am`, but it can ask the package manager whether the component
                // resolves, and (below) start it.
                .put("launchTargets", launchTargets(context))

        val action = call.args["action"].orEmpty()
        if (action != "launch") {
            return ApiOutcome.ok(result)
        }

        // Two forms, mirroring `am start`: an explicit `component=pkg/pkg.Class`, or an
        // `intent=<action>` (e.g. android.settings.APPLICATION_DEVELOPMENT_SETTINGS). The
        // action form is what an operator types to reach a settings screen without knowing
        // the class, and it is the one Android treats as a system-settings intent.
        val component = call.args["component"].orEmpty()
        val intentAction = call.args["intent"].orEmpty()
        if (component.isEmpty() && intentAction.isEmpty()) {
            return ApiOutcome.fail(
                BridgeErrorKind.INVALID_ARGS,
                "component or intent is required for launch",
            )
        }

        return ApiOutcome.ok(launch(context, component, intentAction, result))
    }

    /**
     * Resolves a fixed list of well-known components and reports whether each exists.
     *
     * These are the OEM and settings entry points an operator probes for by hand with
     * `am start -n`; an app cannot run `am`, but the package manager answers the same
     * question — is the package installed, and does the activity resolve. A component
     * that does not resolve is the answer "this build stripped it", which is exactly
     * what a recon pass wants to know.
     */
    private fun launchTargets(context: Context): JSONArray {
        val pm = context.packageManager
        val array = JSONArray()

        for (target in TARGETS) {
            val name = ComponentName(target.pkg, target.cls)
            val installed = runCatching { pm.getPackageInfo(target.pkg, 0) }.isSuccess
            val resolvable =
                installed &&
                    runCatching {
                        pm.resolveActivity(
                            android.content.Intent().setComponent(name),
                            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                        ) != null
                    }.getOrDefault(false)

            array.put(
                JSONObject()
                    .put("name", target.label)
                    .put("packageName", target.pkg)
                    .put("component", name.flattenToString())
                    .put("installed", installed)
                    .put("resolvable", resolvable),
            )
        }

        return array
    }

    /**
     * Starts an activity the way `am start` would, and reports what happened.
     *
     * With [intentAction] set, an action intent is built (`Intent(action)`); otherwise
     * [component] is resolved with `ComponentName.unflattenFromString`. `started` is
     * "startActivity did not throw" — which is **not** the same as "it reached the screen":
     * Android can drop a background activity start without throwing, so a caller that needs
     * proof must observe the resumed activity. `reason` carries the platform's message on a
     * refusal.
     */
    private fun launch(
        context: Context,
        component: String,
        intentAction: String,
        result: JSONObject,
    ): JSONObject {
        result.put("action", "launch")

        val intent =
            android.content.Intent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

        if (intentAction.isNotEmpty()) {
            intent.action = intentAction
            result.put("intent", intentAction)
        } else {
            val parsed = ComponentName.unflattenFromString(component)
            if (parsed == null) {
                return result
                    .put("started", false)
                    .put("reason", "not a valid component (pkg/pkg.Class)")
            }
            intent.component = parsed
            result.put("component", component)
        }

        return try {
            context.startActivity(intent)
            result.put("started", true)
        } catch (e: android.content.ActivityNotFoundException) {
            result.put("started", false).put("reason", "no such activity: ${e.message}")
        } catch (e: SecurityException) {
            result
                .put("started", false)
                .put("reason", "the platform refused the start: ${e.message}")
        }
    }

    /** One probeable component. */
    private class Target(
        val label: String,
        val pkg: String,
        val cls: String,
    )

    /**
     * The well-known components a recon pass checks, with the names an operator types to
     * `am start -n`. A fixed list so the answer is consistent; each is reported whether or
     * not it exists.
     */
    private val TARGETS: List<Target> =
        listOf(
            Target(
                "Unisoc EngineerMode",
                "com.sprd.engineermode",
                "com.sprd.engineermode.EngineerModeActivity",
            ),
            Target(
                "Unisoc LogManager (YLog)",
                "com.sprd.logmanager",
                "com.sprd.logmanager.LogManagerActivity",
            ),
            Target(
                "Developer options",
                "com.android.settings",
                "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity",
            ),
            Target(
                "Developer options (alt)",
                "com.android.settings",
                "com.android.settings.DevelopmentSettingsDashboardActivity",
            ),
        )

    /** The active user restrictions, as a JSON array of keys. */
    private fun activeRestrictions(context: Context): JSONArray {
        val users = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val bundle = users?.userRestrictions ?: return JSONArray()

        val array = JSONArray()
        for (key in bundle.keySet().sorted()) {
            if (bundle.getBoolean(key, false)) array.put(key)
        }

        return array
    }

    /** `sysui_qs_tiles` is a comma-separated tile list. */
    private fun parseTiles(raw: String?): JSONArray {
        val array = JSONArray()
        raw
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { array.put(it) }

        return array
    }

    private fun isDeviceOwner(context: Context): Boolean =
        (
            context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? android.app.admin.DevicePolicyManager
        )?.isDeviceOwnerApp(context.packageName) ?: false

    private const val KEY_QS_TILES = "sysui_qs_tiles"

    /**
     * The SystemUI-gating keys, each with what it controls.
     *
     * A fixed list so a caller learns about a key it would not have asked for; `present`
     * reports whether it is set, so "SystemUI is using its default" is distinguishable
     * from "SystemUI is configured this way".
     */
    private val KEYS: List<SettingKey> =
        listOf(
            SettingKey("secure", "sysui_qs_tiles", "Quick Settings tile layout the panel renders"),
            SettingKey("secure", "qs_auto_tiles", "Quick Settings tiles SystemUI auto-added"),
            SettingKey("secure", "accessibility_qs_targets", "Accessibility QS shortcuts"),
            SettingKey("secure", "enabled_notification_listeners", "Apps with notification access"),
            SettingKey("secure", "enabled_accessibility_services", "Accessibility services in use"),
            SettingKey("secure", "user_setup_complete", "Whether the setup wizard finished"),
            SettingKey("secure", "install_non_market_apps", "Whether sideloading is allowed"),
            SettingKey("secure", "mock_location", "Whether a mock-location provider is allowed"),
            SettingKey("secure", "skip_first_use_hints", "Whether first-use hints are suppressed"),
            SettingKey("secure", "always_finish_activities", "Whether activities finish on leave"),
            SettingKey("global", "adb_enabled", "USB debugging"),
            SettingKey("global", "development_settings_enabled", "Developer options"),
            SettingKey("global", "device_provisioned", "Whether the device finished provisioning"),
            SettingKey("global", "stay_on_while_plugged_in", "Screen stays on while charging"),
            SettingKey("global", "policy_control", "Device-owner screen-suppression list"),
        )
}
