package com.vyzorix.permissions

/**
 * The permission class an API needs, named with the same vocabulary the server's
 * capability catalog uses (`protection:*` in the boundary contract). Sharing the
 * words is deliberate: the Android permission an API requests and the capability
 * tag the server checks must not drift into two different classifications of the
 * same API.
 */
enum class Protection {
    /** No runtime permission; the API is callable immediately. */
    NONE,

    /** A normal permission, granted at install. */
    NORMAL,

    /** A dangerous permission: a runtime dialog, and revocable. */
    DANGEROUS,

    /** Granted only to a signature-matching app. */
    SIGNATURE,
}

/**
 * One API's permission requirement.
 *
 * [androidPermission] is empty when no runtime permission is needed. [minSdk] is
 * the API level the permission exists from — below it the permission is not
 * declared and must not be requested, or the request is silently ignored.
 * [settingsAction] is set when the only way to grant it is a Settings screen
 * rather than a dialog (all-files access, battery optimisation).
 */
data class PermissionDef(
    val androidPermission: String,
    val minSdk: Int,
    val protection: Protection,
    val settingsAction: String? = null,
)

/**
 * Maps every bridge API to its permission requirement. This is the single source
 * of truth: no handler calls `requestPermissions` itself, so the 120-API surface
 * cannot scatter permission logic across 120 files.
 *
 * An API absent from this map needs no permission. That is the same fail-closed
 * shape as the server catalog — an unknown API is not silently granted anything.
 */
object PermissionRegistry {

    private val defs: Map<String, PermissionDef> =
        mapOf(
            // The Build 2 five: chosen precisely because they need no runtime
            // permission, so the bridge stayed testable from adb without a UI.
            "battery" to PermissionDef("", 0, Protection.NONE),
            "device_info" to PermissionDef("", 0, Protection.NONE),
            "storage" to PermissionDef("", 0, Protection.NONE),
            "network" to
                PermissionDef("android.permission.ACCESS_NETWORK_STATE", 1, Protection.NORMAL),
            "logcat" to PermissionDef("android.permission.READ_LOGS", 1, Protection.SIGNATURE),
            "session" to PermissionDef("", 0, Protection.NONE),
            // Permission-gated APIs, registered here rather than in their own
            // handlers. Each is added with its API, never before: a registry entry
            // with no API is as inert as a tag nothing requires.
            // The APIs that need a dangerous permission, each registered with the
            // API that introduced it. A permission-gated API that is not here would be
            // called with no permission and fail at the platform with a
            // SecurityException, which reads as a broken handler rather than a
            // missing grant.
            "camera" to PermissionDef("android.permission.CAMERA", 1, Protection.DANGEROUS),
            // The adapter-state trio. WifiStateApi and NfcStateApi read normal
            // permissions, so there is no dialog — but a normal permission still has
            // to be declared, and a declared one still has to be checked, or the
            // handler calls a platform method it cannot.
            "wifi_state" to
                PermissionDef("android.permission.ACCESS_WIFI_STATE", 1, Protection.NORMAL),
            "nfc_state" to PermissionDef("android.permission.NFC", 1, Protection.NORMAL),
            // Bluetooth is the awkward one: BLUETOOTH is normal and covers every
            // level, but `isEnabled()` needs BLUETOOTH_CONNECT (dangerous) from API
            // 31. The registry names the permission that exists across the whole
            // range, so the gate never wrongly refuses; the handler catches the 31+
            // SecurityException and reports the state as unknown rather than off.
            "bluetooth_state" to
                PermissionDef("android.permission.BLUETOOTH", 1, Protection.NORMAL),
            // READ_BASIC_PHONE_STATE, not READ_PHONE_STATE. The basic permission is
            // API 33+, protection `normal` (no dialog), and covers the operator and
            // SIM facts this API reports. The dangerous half is `phone_state_detailed`.
            "phone_state" to
                PermissionDef(
                    "android.permission.READ_BASIC_PHONE_STATE",
                    33,
                    Protection.NORMAL,
                ),
            // The fields that need the dangerous grant: software version, network
            // type, roaming. Separate API so declining the dialog costs only these.
            "phone_state_detailed" to
                PermissionDef("android.permission.READ_PHONE_STATE", 1, Protection.DANGEROUS),
            // SEND_SMS is what `SmsManager.getDefault()` requires; without it the
            // capability question cannot be answered truthfully.
            "sms_capability" to
                PermissionDef("android.permission.SEND_SMS", 1, Protection.DANGEROUS),
            // RECEIVE_SMS is separate from SEND_SMS on every API level, and an app
            // that can send cannot necessarily receive.
            "sms_received" to
                PermissionDef("android.permission.RECEIVE_SMS", 1, Protection.DANGEROUS),
            // READ_SMS reads the stored inbox, which neither sending nor receiving
            // grants: it is its own dangerous permission on every API level.
            "sms_read" to
                PermissionDef("android.permission.READ_SMS", 1, Protection.DANGEROUS),
            "location" to
                PermissionDef("android.permission.ACCESS_FINE_LOCATION", 1, Protection.DANGEROUS),
            "notification" to
                PermissionDef("android.permission.POST_NOTIFICATIONS", 33, Protection.DANGEROUS),
            "storage_all_files" to
                PermissionDef(
                    "android.permission.MANAGE_EXTERNAL_STORAGE",
                    30,
                    Protection.DANGEROUS,
                    settingsAction = "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                ),
            "battery_optimization" to
                PermissionDef(
                    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                    23,
                    Protection.NORMAL,
                    settingsAction = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                ),
            // USB. The Android permission is per-device and granted through a dialog
            // the app raises with a PendingIntent; there is no manifest permission
            // for "use a USB device", so the entry is empty and the handler reports a
            // failed open when the grant is absent. The capability is the fd, not a
            // runtime permission.
            "usb_state" to PermissionDef("", 0, Protection.NONE),
            "usb_attach" to PermissionDef("", 0, Protection.NONE),
            "usb_detach" to PermissionDef("", 0, Protection.NONE),
        )

    /** The requirement for [api], or null when the API is unknown. */
    fun forApi(api: String): PermissionDef? = defs[api]

    /**
     * Every dangerous permission that a runtime dialog can grant, for a device
     * running [sdkInt].
     *
     * This is the set the setup Activity requests. It exists so the requested set is
     * **derived** from the registry rather than hand-listed beside it: a dangerous
     * permission that is declared but never requested is granted to nobody, so every
     * API gated on it returns `FORBIDDEN` forever — which is exactly what happened
     * to `camera`, `location`, `call_capability`, `phone_state_detailed`,
     * `sms_capability`, `sms_received` and `sms_read` while the Activity asked for
     * four permissions by hand.
     *
     * Settings-only permissions ([PermissionDef.settingsAction] set, e.g. all-files
     * access) are excluded: `requestPermissions` cannot grant them, so listing them
     * would show a dialog that does nothing. The API's own handler opens the Settings
     * screen when it is needed.
     *
     * Distinct and sorted, so the dialog order is stable and one permission declared
     * by two APIs is requested once.
     */
    fun dialogGrantablePermissions(sdkInt: Int): List<String> =
        defs
            .values
            .asSequence()
            .filter { it.protection == Protection.DANGEROUS }
            .filter { it.androidPermission.isNotEmpty() }
            .filter { it.settingsAction == null }
            .filter { sdkInt >= it.minSdk }
            .map { it.androidPermission }
            .distinct()
            .sorted()
            .toList()

    /** Every API this registry knows, sorted. Used by tests and introspection. */
    fun names(): List<String> = defs.keys.sorted()

    /**
     * True when [api] needs no runtime permission, including when it is unknown:
     * an API with no entry requests nothing, and the handler decides for itself.
     *
     * **Structural, not SDK-aware.** This answers "does this API have a permission
     * requirement at all", which is a property of the catalogue. Whether that
     * permission *applies on the running device* is [requiresPermissionOn], a
     * different question with a different answer per API level. Keeping them apart
     * matters: a plain-JVM test has `SDK_INT == 0`, so folding the SDK rule in here
     * would report every permission as not-yet-existing and make the registry
     * untestable without Robolectric.
     */
    fun needsNoPermission(api: String): Boolean {
        val def = defs[api] ?: return true

        return def.androidPermission.isEmpty()
    }

    /**
     * True when [api]'s permission is required on a device running [sdkInt].
     *
     * A permission that does not exist below its `minSdk` is not required there: the
     * platform has no such permission, so a granted-check reports it denied and the
     * API would be uncallable on a device where it is in fact fine (`phone_state` on
     * API 26-32 is the case — it gates on `READ_BASIC_PHONE_STATE`, which arrived in
     * 33). The SDK is a parameter rather than read from `Build` so the rule is
     * testable at any level, including in a plain JVM test.
     *
     * False for an API that needs no permission at all, so a caller can use this as
     * the single gate.
     */
    fun requiresPermissionOn(
        api: String,
        sdkInt: Int,
    ): Boolean {
        val def = defs[api] ?: return false
        if (def.androidPermission.isEmpty()) return false

        return sdkInt >= def.minSdk
    }
}
