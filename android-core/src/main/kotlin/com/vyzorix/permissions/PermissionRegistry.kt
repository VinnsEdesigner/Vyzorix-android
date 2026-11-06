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
            "phone_state" to
                PermissionDef("android.permission.READ_PHONE_STATE", 1, Protection.DANGEROUS),
            // SEND_SMS is what `SmsManager.getDefault()` requires; without it the
            // capability question cannot be answered truthfully.
            "sms_capability" to
                PermissionDef("android.permission.SEND_SMS", 1, Protection.DANGEROUS),
            // RECEIVE_SMS is separate from SEND_SMS on every API level, and an app
            // that can send cannot necessarily receive.
            "sms_received" to
                PermissionDef("android.permission.RECEIVE_SMS", 1, Protection.DANGEROUS),
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
        )

    /** The requirement for [api], or null when the API is unknown. */
    fun forApi(api: String): PermissionDef? = defs[api]

    /** Every API this registry knows, sorted. Used by tests and introspection. */
    fun names(): List<String> = defs.keys.sorted()

    /**
     * True when [api] needs no runtime permission, including when it is unknown:
     * an API with no entry requests nothing, and the handler decides for itself.
     */
    fun needsNoPermission(api: String): Boolean {
        val def = defs[api] ?: return true

        return def.androidPermission.isEmpty()
    }
}
