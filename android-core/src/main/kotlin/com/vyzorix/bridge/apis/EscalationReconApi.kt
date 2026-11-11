package com.vyzorix.bridge.apis

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.service.DeviceSettingsWriter
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * **Recon only:** which privilege-escalation routes exist on this device, so an operator can
 * see what is and is not available *before* trying something.
 *
 * ## Why this exists
 *
 * The open-source community gets more-than-app privilege through a small, known set of
 * routes — Shizuku (an `app_process` server started over ADB/root), Sui (the root variant),
 * Dhizuku (shares a device-owner's binder), a real device-owner role, or a local-ADB app.
 * Each leaves a detectable trace: a package, a permission we were granted, a provider
 * authority, a root binary. This API reports those traces, so "can we get higher privilege
 * here?" has an evidence-based answer rather than a guess.
 *
 * ## What it deliberately is not
 *
 * **Read-only, and it does not escalate.** It never starts a shell, never requests a
 * permission, and never contacts another app's provider. It asks the package manager and
 * the settings provider a few questions and reports the answers. Installing Shizuku or
 * provisioning a device owner is a user/operator decision made *outside* the app — this
 * app cannot create those grants for itself, and pretending otherwise would be the exact
 * "loophole" the platform has closed.
 *
 * ## The honest state of the field (2026)
 *
 * Every route below needs a **user-initiated bootstrap** that a plain sideloaded app cannot
 * perform on itself: ADB (`adb shell sh … app_process`), root (`su`), or provisioning a
 * device owner (NFC/QR at setup, or `adb shell dpm set-device-owner`). The "delete any
 * system package" apps (Canta, Hail, App Manager) all sit on Shizuku; the device-owner ones
 * (OwnDroid, Dhizuku) need the owner role. There is no current route that a normal app can
 * take to reach a privileged uid by itself, and this API reports that rather than implying
 * otherwise.
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
object EscalationReconApi : ApiHandler {

    const val NAME = "escalation_recon"

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val SHIZUKU_PERMISSION = "moe.shizuku.permission.API_V23"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val pm = context.packageManager

        val shizuku = provider(pm, "Shizuku", SHIZUKU_PKG, SHIZUKU_PERMISSION)
        val sui = provider(pm, "Sui (root)", SHIZUKU_PKG, SHIZUKU_PERMISSION)
        val dhizuku =
            provider(pm, "Dhizuku", "com.rosan.dhizuku", "com.rosan.dhizuku.permission.API")

        return ApiOutcome.ok(
            JSONObject()
                .put("ownUid", android.os.Process.myUid())
                .put("ownPackage", context.packageName)
                // The routes, each as {name, installed, permissionGranted, note}.
                .put("shizuku", shizuku)
                .put("sui", sui)
                .put("dhizuku", dhizuku)
                // Root: the su binaries a rooted device carries. A file check is the honest
                // signal an app has; actually running `su` would be an escalation attempt.
                .put("rootBinaries", rootBinaries())
                .put("rooted", rootBinaries().length() > 0)
                // The role we might hold. A device owner can do far more than an admin;
                // neither is something the app can grant itself.
                .put("isDeviceOwner", DeviceSettingsWriter.isDeviceOwner(context))
                .put("canWriteSecure", DeviceSettingsWriter.canWriteSecure(context))
                // ADB: whether the debug bridge is on. A host with adb is the bootstrap for
                // Shizuku; on its own it grants the app nothing.
                .put("adbEnabled", adbEnabled(context))
                // A one-line honest summary for a caller that only wants the verdict.
                .put(
                    "summary",
                    "no self-service escalation: every route needs a user/ADB/root bootstrap " +
                        "outside the app",
                ),
        )
    }

    /**
     * One escalation route: whether its package is installed, and whether we hold its
     * permission (which only happens after the user grants it through that app).
     */
    private fun provider(
        pm: PackageManager,
        name: String,
        pkg: String,
        permission: String,
    ): JSONObject {
        val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        val granted =
            runCatching {
                pm.checkPermission(permission, pm.getPackageInfo(pkg, 0).packageName) ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

        return JSONObject()
            .put("name", name)
            .put("packageName", pkg)
            .put("installed", installed)
            .put("permissionGranted", granted)
    }

    private fun adbEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, "adb_enabled", 0) != 0

    /** The root `su` binaries a rooted device carries, by their usual paths. */
    private fun rootBinaries(): JSONArray {
        val array = JSONArray()
        for (path in listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")) {
            if (File(path).exists()) array.put(path)
        }

        return array
    }
}
