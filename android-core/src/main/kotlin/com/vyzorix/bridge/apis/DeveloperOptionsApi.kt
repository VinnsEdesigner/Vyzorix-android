package com.vyzorix.bridge.apis

import android.content.ContentResolver
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.service.DeviceSettingsWriter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Developer options: whether they are on, whether USB debugging is on, whether a
 * policy **blocks** them, and whether they can be enabled at all.
 *
 * ## The question this answers
 *
 * "Developer options are off" is only half an answer: they may be off because the
 * user has not enabled them (in which case they can be turned on), or because a
 * device-owner policy forbids debugging (in which case they cannot). Those are
 * different situations and a user acts on them differently, so both are
 * reported:
 *
 *  - [KEY_DEV_SETTINGS] / [KEY_ADB] — the current state.
 *  - `debuggingBlockedByPolicy` — the `DISALLOW_DEBUGGING_FEATURES` user
 *    restriction is set, which a device owner can impose. When true, developer
 *    options and ADB cannot be enabled, by the user or anyone.
 *  - `canEnable` — the inverse: nothing blocks them, so the user can turn them on
 *    (seven taps on the build number). This app cannot enable them itself — writing
 *    `development_settings_enabled` needs `WRITE_SECURE_SETTINGS`, a signature
 *    permission — so "can be enabled" is reported as the user's ability, not ours.
 *
 * The full active user-restriction set is dumped too, since it is the same read and
 * is what a policy audit wants.
 *
 * Read-only. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object DeveloperOptionsApi : ApiHandler {

    const val NAME = "developer_options"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val resolver = context.contentResolver

        val blocked = debuggingBlockedByPolicy(context)
        val canWrite = DeviceSettingsWriter.canWriteGlobal(context)

        val result =
            JSONObject()
                .put("developerOptions", isOn(resolver, KEY_DEV_SETTINGS))
                .put("adbEnabled", isOn(resolver, KEY_ADB))
                // The dev-option-adjacent toggles a user may be looking for.
                .put("adbWifiEnabled", isOn(resolver, KEY_ADB_WIFI))
                .put("stayAwakeWhilePluggedIn", isOn(resolver, KEY_STAY_AWAKE))
                .put("wirelessDebuggingEnabled", isOn(resolver, KEY_ADB_WIFI))
                // The policy gate, and its inverse.
                .put("debuggingBlockedByPolicy", blocked)
                .put("canEnable", !blocked)
                // Whether this app can *write* the setting at all: only a device owner
                // (via setGlobalSetting) or a WRITE_SECURE_SETTINGS holder can. A plain
                // app reports false and the enable action below is a no-op.
                .put("canWriteSetting", canWrite)
                .put("isDeviceOwner", DeviceSettingsWriter.isDeviceOwner(context))
                .put("userRestrictions", userRestrictions(context))

        // `enable=true` turns developer options on when it can. It is opt-in so a plain
        // status read never changes the device; a caller that wants the write asks for
        // it. The result reports what happened rather than throwing, because the
        // interesting answer is "could not, and why".
        if (call.args["enable"]?.toBoolean() == true) {
            result.put("enabled", enableDeveloperOptions(context, blocked, canWrite))
        }

        return ApiOutcome.ok(result)
    }

    /**
     * Turns developer options on, when it is possible.
     *
     * Two gates: a policy must not forbid debugging, and the app must be able to write
     * the global setting (device owner or `WRITE_SECURE_SETTINGS`). Returns true only
     * when the write succeeded; the caller sees the same flag it would from a status
     * read, so it can confirm.
     */
    private fun enableDeveloperOptions(
        context: Context,
        blocked: Boolean,
        canWrite: Boolean,
    ): Boolean {
        if (blocked || !canWrite) return false

        return DeviceSettingsWriter.setGlobal(context, KEY_DEV_SETTINGS, "1")
    }

    /**
     * True when a policy forbids debugging.
     *
     * `DISALLOW_DEBUGGING_FEATURES` is the restriction a device owner sets to lock
     * out developer options and ADB. Read from the user restrictions, which need no
     * permission to read for the current user.
     */
    private fun debuggingBlockedByPolicy(context: Context): Boolean {
        val manager =
            context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return false

        return manager.userRestrictions.getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES, false)
    }

    /** Every active user restriction, as `key -> value`. */
    private fun userRestrictions(context: Context): JSONObject {
        val manager =
            context.getSystemService(Context.USER_SERVICE) as? UserManager
                ?: return JSONObject()

        val bundle = manager.userRestrictions
        val keys = JSONArray()

        for (key in bundle.keySet().sorted()) {
            keys.put(JSONObject().put("key", key).put("value", bundle.get(key)?.toString() ?: ""))
        }

        return JSONObject().put("count", keys.length()).put("entries", keys)
    }

    private fun isOn(
        resolver: ContentResolver,
        key: String,
    ): Boolean = Settings.Global.getInt(resolver, key, 0) != 0

    private const val KEY_DEV_SETTINGS = "development_settings_enabled"
    private const val KEY_ADB = "adb_enabled"
    private const val KEY_ADB_WIFI = "adb_wifi_enabled"
    private const val KEY_STAY_AWAKE = "stay_on_while_plugged_in"
}
