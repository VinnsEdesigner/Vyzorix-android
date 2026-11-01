package com.vyzorix.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The only place in the module that asks the system for a permission.
 *
 * A handler calls [ensure] and is told whether it may proceed. Nothing else calls
 * `requestPermissions`, so the decision and the dialog live in one file rather
 * than being re-implemented per API.
 *
 * The service is headless, so a runtime dialog has no Activity to attach to. The
 * two paths that need UI therefore need an Activity to be supplied: a transparent
 * one that the host app provides. When none is available the request resolves to
 * "denied" rather than hanging — a bridge call must always return.
 */
class PermissionRequester(
    private val context: Context,
    /**
     * Launches a permission Activity and resolves with the user's answer. Supplied
     * by the app module, which owns the Activity. Null in a headless-only build,
     * where [ensure] then denies rather than blocking.
     */
    private val requestViaActivity: (suspend (PermissionDef) -> Boolean)? = null,
) {

    /**
     * Resolves whether [api] may run, requesting the permission if needed.
     *
     * Returns true for an API that needs no permission, or one already granted.
     * For a permission granted only through a Settings screen, opens that screen
     * and returns false: the user must come back and retry, because the result
     * cannot be observed from here.
     */
    suspend fun ensure(api: String): Boolean {
        val def = PermissionRegistry.forApi(api) ?: return true
        if (def.androidPermission.isEmpty()) return true

        // A permission that does not exist below its minSdk must not be requested;
        // the request is silently ignored and would look like a denial.
        if (Build.VERSION.SDK_INT < def.minSdk) return true

        if (isGranted(def.androidPermission)) return true

        // Settings-only permissions cannot be granted by a dialog. Open the screen
        // and report "not yet" — the caller retries after the user returns.
        if (def.settingsAction != null) {
            openSettings(def)

            return false
        }

        val requester = requestViaActivity ?: return false

        return requester(def)
    }

    /** True when [permission] is already granted. */
    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun openSettings(def: PermissionDef) {
        val action = def.settingsAction ?: return
        val intent =
            android.content.Intent(action).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        // A device without the settings screen (an OEM that renamed it) must not
        // crash the bridge; the caller already treats this as "not granted".
        runCatching { context.startActivity(intent) }
    }

    companion object {
        /**
         * The permission names an Activity must forward to
         * `Activity.requestPermissions`, derived from the registry so the Activity
         * does not carry its own list.
         */
        fun requestedPermissions(api: String): Array<String> {
            val def = PermissionRegistry.forApi(api) ?: return emptyArray()
            if (def.androidPermission.isEmpty()) return emptyArray()
            if (Build.VERSION.SDK_INT < def.minSdk) return emptyArray()

            return arrayOf(def.androidPermission)
        }

        /** True when the result of [requestPermissions] granted everything asked for. */
        fun allGranted(
            permissions: Array<String>,
            grantResults: IntArray,
        ): Boolean {
            if (permissions.isEmpty()) return true
            if (grantResults.isEmpty()) return false
            if (grantResults.size != permissions.size) return false

            return grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        }
    }
}
