package com.vyzorix.bridge.apis

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * This app's **app-op** modes: whether each privileged operation the agent performs is
 * allowed, denied, or on its default.
 *
 * ## Why app-ops, not permissions
 *
 * Some of the most consequential switches on Android are not permissions and not user
 * restrictions — they are app-ops, held in the platform's appops store
 * (`/data/system/appops.xml`). "Display over other apps" is `SYSTEM_ALERT_WINDOW`, an
 * app-op; so is `RUN_IN_BACKGROUND`, `WAKE_LOCK`, `START_FOREGROUND` and the SMS/call
 * ops. They never appear in `Settings` or `UserManager`, so nothing else in the bridge
 * can report them, and a permission that reads `granted` can still be **op-denied** at
 * the point of use.
 *
 * ## Scope: our own package only
 *
 * An app can read the op modes **for its own package**; reading another package's is
 * privileged. So this reports `context.packageName`'s ops, which is exactly the question
 * "are *our* operations being allowed". A caller that needs another app's overlay state
 * cannot get it here — that needs `dumpsys appops` (a host with adb) or a device owner.
 *
 * ## The modes
 *
 * Each op reports its [AppOpsManager] mode: `allowed`, `ignored` (denied), `default`
 * (the platform default applies), `foreground` (allowed only in the foreground), or
 * `errored`. `default` is not "granted" — it means the runtime-permission state decides.
 *
 * Read-only: it never changes an op. `noteOp`/`startOp` (which would consume a one-shot
 * allowance) are deliberately not called; `checkOpNoThrow` is a pure read.
 *
 * No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PRIVATE,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object AppOpsApi : ApiHandler {

    const val NAME = "app_ops"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val manager =
            context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no app-ops service")

        val uid = Process.myUid()
        val pkg = context.packageName

        val array = JSONArray()
        var denied = 0
        for (op in OPS) {
            val (mode, detail) = modeOf(manager, op.op, uid, pkg)
            if (mode == "ignored" || mode == "errored") denied++
            val entry =
                JSONObject()
                    .put("op", op.name)
                    .put("mode", mode)
                    .put("meaning", op.meaning)
            // Only present when the read itself had something to say (an unknown op, a
            // platform error); an ordinary mode carries no detail.
            if (detail.isNotEmpty()) entry.put("detail", detail)
            array.put(entry)
        }

        return ApiOutcome.ok(
            JSONObject()
                .put("packageName", pkg)
                .put("uid", uid)
                .put("ops", array)
                .put("count", array.length())
                // The count that matters: operations the platform is actively denying us.
                .put("deniedCount", denied)
                .put("anyDenied", denied > 0),
        )
    }

    /**
     * The op's mode name and any detail. `checkOpNoThrow` is a pure read.
     *
     * An op *name* the platform does not know throws `IllegalArgumentException`; any
     * other throwable is reported as errored. Both are facts about the op, not a failure
     * of the read, so the detail is carried out rather than lost.
     */
    private fun modeOf(
        manager: AppOpsManager,
        op: String,
        uid: Int,
        pkg: String,
    ): Pair<String, String> {
        val mode =
            try {
                @Suppress("DEPRECATION")
                manager.checkOpNoThrow(op, uid, pkg)
            } catch (e: IllegalArgumentException) {
                return "unknown" to e.message.orEmpty()
            } catch (e: Throwable) {
                return "errored" to e.message.orEmpty()
            }

        return modeName(mode) to ""
    }

    private fun modeName(mode: Int): String =
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_IGNORED -> "ignored"
            AppOpsManager.MODE_DEFAULT -> "default"
            AppOpsManager.MODE_FOREGROUND -> "foreground"
            AppOpsManager.MODE_ERRORED -> "errored"
            else -> "unknown"
        }

    /** `MODE_ERRORED` is not on the public `AppOpsManager` surface; its value is stable. */
    private const val MODE_ERRORED = 2

    /** One op: its platform op-string, a readable name, and what it gates. */
    private class Op(
        val op: String,
        val name: String,
        val meaning: String,
    )

    /**
     * The ops the agent's own features depend on, each with what it gates.
     *
     * The op string is the platform's `OPSTR_*` *value* (e.g. `android:system_alert_window`)
     * — what `checkOpNoThrow(String, ...)` keys on, and what `dumpsys appops` prints. The
     * literals are used rather than the `AppOpsManager.OPSTR_*` constants because most of
     * those constants are not on the public SDK; the values themselves are AOSP-stable and
     * appear verbatim in the platform's app-op table. An op the device does not know (added
     * after this build) simply reads as `unknown`, so the list is safe across API levels.
     */
    private val OPS: List<Op> =
        listOf(
            Op(
                "android:system_alert_window",
                "SYSTEM_ALERT_WINDOW",
                "Display over other apps (overlay)",
            ),
            Op("android:run_in_background", "RUN_IN_BACKGROUND", "Run in the background"),
            Op("android:wake_lock", "WAKE_LOCK", "Hold a wake lock"),
            Op("android:start_foreground", "START_FOREGROUND", "Start a foreground service"),
            Op("android:post_notification", "POST_NOTIFICATION", "Post notifications"),
            Op(
                "android:request_install_packages",
                "REQUEST_INSTALL_PACKAGES",
                "Request package installs (self-update)",
            ),
            Op("android:get_usage_stats", "GET_USAGE_STATS", "Read usage statistics"),
            Op("android:manage_external_storage", "MANAGE_EXTERNAL_STORAGE", "All-files access"),
            Op("android:read_external_storage", "READ_EXTERNAL_STORAGE", "Read shared storage"),
            Op("android:write_external_storage", "WRITE_EXTERNAL_STORAGE", "Write shared storage"),
            Op("android:send_sms", "SEND_SMS", "Send SMS"),
            Op("android:receive_sms", "RECEIVE_SMS", "Receive SMS"),
            Op("android:read_sms", "READ_SMS", "Read the SMS inbox"),
            Op("android:read_call_log", "READ_CALL_LOG", "Read the call log"),
            Op("android:write_call_log", "WRITE_CALL_LOG", "Write the call log"),
            Op("android:call_phone", "CALL_PHONE", "Place calls"),
            Op("android:camera", "CAMERA", "Use the camera"),
            Op("android:record_audio", "RECORD_AUDIO", "Record audio"),
            Op("android:coarse_location", "COARSE_LOCATION", "Approximate location"),
            Op("android:fine_location", "FINE_LOCATION", "Precise location"),
            Op("android:read_contacts", "READ_CONTACTS", "Read contacts"),
            Op("android:project_media", "PROJECT_MEDIA", "Project media (cast)"),
            Op(
                "android:use_full_screen_intent",
                "USE_FULL_SCREEN_INTENT",
                "Use a full-screen intent",
            ),
        )
}
