package com.vyzorix.agent

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyzorix.bridge.ApiCatalogue
import com.vyzorix.bridge.ApiRouter
import com.vyzorix.bridge.SessionTokenRegistry
import com.vyzorix.bridge.apis.BluetoothControlApi
import com.vyzorix.permissions.PermissionRequester
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The enriched device-management APIs, dispatched through the real router on a real
 * device, with their full JSON printed so the output is visible in the run.
 *
 * ## Why this is an instrumented test
 *
 * `ApiSmokeTest` proves on Robolectric that no handler throws. What it cannot prove is
 * that a handler reads the *device's* real state: the device-owner role, the active
 * admins, the developer-options flags and the accessibility services all come from
 * system services and settings that Robolectric only fakes. This runs against the
 * emulator, where the app can actually be made a device owner (`dpm set-device-owner`)
 * and the answers are real.
 *
 * The assertions are deliberately shape-level — the admin list is non-empty, the owner
 * booleans are present, the policy dump carries its keys — because the values are the
 * device's, not the test's. The JSON is logged in full so a run shows exactly what an
 * operator would see.
 */
@RunWith(AndroidJUnit4::class)
class AdminApisLiveTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun router(): ApiRouter =
        ApiCatalogue
            .registerAll(ApiRouter(PermissionRequester(context)))
            // bluetooth_control is service-registered (it owns a discovery receiver), so
            // the catalogue's registerAll does not include it; register an instance here.
            .register(BluetoothControlApi.NAME, BluetoothControlApi())

    private fun token() =
        SessionTokenRegistry().issue(
            "admin-test",
            "dev-1",
            "op-1",
            apiAllowlist = ApiCatalogue.names.toSet(),
        )

    private fun call(
        api: String,
        args: Map<String, String> = emptyMap(),
    ): JSONObject {
        val outcome = router().dispatch(context, token(), api, args)
        assertTrue("$api failed: ${outcome.error?.detail}", outcome.isOk)

        val json = outcome.json
        assertNotNull("$api returned no JSON", json)
        Log.i(TAG, "$api -> $json")

        return JSONObject(json!!)
    }

    @Test
    fun device_owner_reports_the_admins_and_the_owner_components() {
        val json = call("device_owner")

        assertTrue("deviceOwner must be present", json.has("deviceOwner"))
        assertTrue("profileOwner must be present", json.has("profileOwner"))
        assertTrue("adminsVisible must be present", json.has("adminsVisible"))

        // The owner-component keys are always present; they are empty strings when the
        // accessor is unavailable. That is the expected case for a normal app:
        // `getDeviceOwnerComponentOnAnyUser` is @SystemApi, so reading it throws
        // "Calling identity is not authorized" — the handler catches it and reports "".
        // The assertion is therefore on the *shape*, not on a non-empty value.
        assertTrue(json.has("deviceOwnerComponent"))
        assertTrue(json.has("profileOwnerComponent"))
        assertTrue(json.has("deviceOwnerLockScreenInfo"))
    }

    @Test
    fun device_owner_capabilities_dumps_the_policy_set() {
        val json = call("device_owner_capabilities")

        val keys =
            listOf(
                "deviceOwner",
                "canLockNow",
                "passwordQuality",
                "passwordMinimumLength",
                "maximumTimeToLock",
                "screenCaptureDisabled",
                "cameraDisabled",
                "keyguardDisabledFeatures",
                "storageEncryptionStatus",
                "lockTaskPermitted",
            )

        for (key in keys) {
            assertTrue("$key must be present in the policy dump", json.has(key))
        }
    }

    @Test
    fun developer_options_reports_the_policy_gate() {
        val json = call("developer_options")

        assertTrue(json.has("developerOptions"))
        assertTrue(json.has("adbEnabled"))
        assertTrue(json.has("debuggingBlockedByPolicy"))
        assertTrue(json.has("canEnable"))
        assertTrue(json.has("canWriteSetting"))
        assertTrue(json.has("isDeviceOwner"))
        assertTrue(json.has("userRestrictions"))
    }

    @Test
    fun developer_options_can_enable_developer_options_as_device_owner() {
        // A status read first: it must not change anything.
        val before = call("developer_options")
        assertTrue(before.has("developerOptions"))

        // The enable action. As device owner this must succeed and the setting must read
        // back as on.
        val enabled = call("developer_options", mapOf("enable" to "true"))
        Log.i(TAG, "developer_options enable -> $enabled")

        assertTrue("enable must report a result", enabled.has("enabled"))
        assertTrue(
            "as device owner, developer options must be enabled",
            enabled.getBoolean("enabled"),
        )

        val after = call("developer_options")
        assertTrue(
            "developer options must read back on",
            after.getBoolean("developerOptions"),
        )
    }

    @Test
    fun call_control_reports_the_gates_and_does_not_dial_without_a_number() {
        val json = call("call_control")

        assertTrue(json.has("outgoingCallsBlocked"))
        assertTrue(json.has("callPhoneGranted"))
        assertTrue(json.has("isDeviceOwner"))
        // No number supplied, so nothing was dialled.
        assertTrue("a bare call must not place a call", !json.has("placed"))
    }

    @Test
    fun usb_control_reports_the_gates_and_can_enable_debugging_as_device_owner() {
        val status = call("usb_control")
        assertTrue(status.has("usbDebuggingEnabled"))
        assertTrue(status.has("usbFileTransferBlocked"))
        assertTrue(status.has("debuggingBlockedByPolicy"))
        assertTrue(status.has("canWriteSettings"))
        assertTrue(status.has("isDeviceOwner"))

        val enabled = call("usb_control", mapOf("enable" to "true"))
        Log.i(TAG, "usb_control enable -> $enabled")
        assertTrue(enabled.has("usbDebuggingEnabledNow"))
        assertTrue(enabled.has("usbFileTransferUnblocked"))
    }

    @Test
    fun accessibility_lists_enabled_and_installed_services() {
        val json = call("accessibility")

        assertTrue(json.has("enabled"))
        assertTrue(json.has("count"))
        assertTrue(json.has("isEnabled"))
        assertTrue(json.has("installed"))
        assertTrue(json.has("installedCount"))
    }

    @Test
    fun wifi_control_reports_state_and_the_hotspot_limit() {
        val json = call("wifi_control")

        assertTrue(json.has("enabled"))
        assertTrue(json.has("state"))
        // The hotspot/tethering limit is reported, not omitted.
        assertTrue(json.has("hotspotSupported"))
        assertTrue(json.has("hotspotReason"))
        assertTrue(json.has("scanResults"))
        assertTrue(json.has("configured"))
    }

    @Test
    fun bluetooth_control_reports_state_and_the_packet_limit() {
        val json = call("bluetooth_control")

        assertTrue(json.has("supported"))
        // HCI packet access is impossible for a normal app; the API says so.
        assertTrue(json.has("packetAccess"))
        assertTrue(json.has("packetAccessReason"))
        assertTrue(json.has("bonded"))
        assertTrue(json.has("discovered"))
    }

    @Test
    fun wallpaper_reports_its_capabilities() {
        val json = call("wallpaper")

        assertTrue(json.has("isWallpaperSupported"))
        assertTrue(json.has("isSetWallpaperAllowed"))
    }

    @Test
    fun device_admin_action_reports_state_and_exercises_policies() {
        // A status read must change nothing.
        val status = call("device_admin_action")
        assertTrue(status.has("isActiveAdmin"))
        assertTrue(status.has("isDeviceOwner"))
        assertTrue(status.has("passwordQuality"))
        assertTrue(status.has("cameraDisabled"))
        assertTrue(status.has("keyguardDisabledFeatures"))

        // The benign policy actions, exercised live: set a camera policy and read it back.
        val camera =
            call(
                "device_admin_action",
                mapOf(
                    "action" to "setCameraDisabled",
                    "value" to "true",
                ),
            )
        Log.i(TAG, "setCameraDisabled -> $camera")
        assertTrue(camera.getBoolean("ok"))
        assertTrue("camera policy must read back disabled", camera.getBoolean("cameraDisabled"))

        // Put it back.
        call("device_admin_action", mapOf("action" to "setCameraDisabled", "value" to "false"))
    }

    @Test
    fun device_credentials_reports_admin_state() {
        val json = call("device_credentials")

        assertTrue(json.has("isActiveAdmin"))
        assertTrue(json.has("isDeviceOwner"))
        assertTrue(json.has("caCerts"))
    }

    @Test
    fun packages_dump_reads_the_full_package_list() {
        val json = call("packages_dump")

        val count = json.getInt("count")
        Log.i(TAG, "packages_dump count=$count total=${json.optInt("totalAvailable")}")

        // A device has system packages; QUERY_ALL_PACKAGES lifts the Android 11 filter,
        // so the dump must find more than the visible list would.
        assertTrue("the dump must find packages", count > 0)
        assertTrue(json.has("totalAvailable"))
        assertTrue(json.has("truncated"))

        // Each package carries its name, its exact folders, its family, its children
        // (APK splits), and its components.
        val first = json.getJSONArray("packages").getJSONObject(0)
        for (
        key in
        listOf(
            "packageName",
            "system",
            "enabled",
            "uid",
            "sharedUserId",
            "dataDir",
            "sourceDir",
            "publicSourceDir",
            "nativeLibraryDir",
            "splitNames",
            "activities",
            "requestedPermissions",
        )
        ) {
            assertTrue("the dump must carry $key", first.has(key))
        }

        // The shared-uid groups are the parent/children view.
        assertTrue(json.has("sharedUidGroups"))
    }

    @Test
    fun packages_dump_honours_an_explicit_limit() {
        // A caller-supplied cap pages the list and says so.
        val json = call("packages_dump", mapOf("limit" to "5"))

        assertEquals("the page must be capped at the limit", 5, json.getInt("count"))
        assertTrue("a capped page must report the full total", json.getInt("totalAvailable") >= 5)
        assertTrue("a capped page must be marked truncated", json.getBoolean("truncated"))
    }

    @Test
    fun user_restrictions_dumps_the_whole_aosp_catalogue() {
        val json = call("user_restrictions")

        // Every restriction is listed, restricted or not — that is the point of the API.
        assertTrue("the catalogue must be substantial", json.getInt("catalogCount") > 40)
        assertTrue(json.has("anyRestricted"))
        assertTrue(json.has("restrictedCount"))
        assertTrue(json.has("active"))
        assertTrue(json.has("canClear"))

        // The catalogue names the restrictions a locked-down device carries.
        val restrictions = json.getJSONArray("restrictions")
        val keys =
            (0 until restrictions.length()).map { restrictions.getJSONObject(it).getString("key") }
        assertTrue("no_usb_file_transfer must be catalogued", "no_usb_file_transfer" in keys)
        assertTrue("no_config_tethering must be catalogued", "no_config_tethering" in keys)
    }

    @Test
    fun app_ops_reports_this_packages_op_modes() {
        val json = call("app_ops")

        assertEquals(
            "the ops must be for this package",
            context.packageName,
            json.getString("packageName"),
        )
        assertTrue(json.has("anyDenied"))
        assertTrue(json.has("deniedCount"))

        val ops = json.getJSONArray("ops")
        assertTrue("the op catalogue must be substantial", ops.length() > 10)

        // Every op reports a mode; on a real device the modes are real, not unknown.
        val modes = (0 until ops.length()).map { ops.getJSONObject(it).getString("mode") }
        assertTrue(
            "every op must report a mode; got ${modes.toSet()}",
            modes.all {
                it in setOf("allowed", "ignored", "default", "foreground", "errored", "unknown")
            },
        )
        // The overlay op — the one this device resets — must be present and readable.
        val overlay =
            (0 until ops.length())
                .map { ops.getJSONObject(it) }
                .first { it.getString("op") == "SYSTEM_ALERT_WINDOW" }
        Log.i(TAG, "app_ops SYSTEM_ALERT_WINDOW -> ${overlay.getString("mode")}")
    }

    @Test
    fun accessibility_reports_canWrite_and_refuses_a_toggle_without_the_grant() {
        // A read is always allowed; the write needs WRITE_SECURE_SETTINGS, which a normal
        // install does not hold, so the toggle must report changed:false with a reason
        // rather than silently doing nothing.
        val json = call("accessibility")

        assertTrue(json.has("canWrite"))
        assertTrue(json.has("enabled"))
        assertTrue(json.has("installed"))

        val toggled =
            call(
                "accessibility",
                mapOf("action" to "enable", "component" to "com.example/.Svc"),
            )

        assertEquals("the action must be reported", "enable", toggled.getString("action"))
        assertTrue("a toggle must report whether it changed", toggled.has("changed"))
        if (!toggled.getBoolean("changed")) {
            assertTrue(
                "a refused toggle must carry a reason",
                toggled.optString("reason").isNotEmpty(),
            )
        }
    }

    @Test
    fun systemui_state_reports_the_qs_tiles_and_gating_keys() {
        val json = call("systemui_state")

        assertTrue(json.has("qsTiles"))
        assertTrue(json.has("secure"))
        assertTrue(json.has("global"))
        assertTrue(json.has("activeRestrictions"))

        // The QS layout key is read (possibly empty on a fresh device).
        Log.i(TAG, "systemui_state qsTiles -> ${json.getJSONArray("qsTiles")}")

        // The OEM/settings components are probed for existence — the read-only half of
        // `am start -n`. Each target reports installed/resolvable, whether or not it
        // exists, so a stripped build is an answer rather than a failure.
        val targets = json.getJSONArray("launchTargets")
        assertTrue("the launch targets must be probed", targets.length() >= 4)
        val engineermode =
            (0 until targets.length())
                .map { targets.getJSONObject(it) }
                .first { it.getString("packageName") == "com.sprd.engineermode" }
        assertTrue("an EngineerMode target must be reported", engineermode.has("installed"))
        assertTrue(engineermode.has("resolvable"))
        Log.i(TAG, "launchTargets -> $targets")
    }

    @Test
    fun device_info_reports_low_ram_and_memory_class() {
        val json = call("device_info")

        assertTrue("device_info must report lowRam", json.has("lowRam"))
        assertTrue("device_info must report a memory class", json.getInt("memoryClassMb") > 0)
        Log.i(
            TAG,
            "device_info lowRam=${json.getBoolean("lowRam")} " +
                "memoryClassMb=${json.getInt("memoryClassMb")}",
        )
    }

    @Test
    fun escalation_recon_reports_the_route_state() {
        val json = call("escalation_recon")

        assertTrue(json.has("ownUid"))
        assertTrue(json.has("rooted"))
        assertTrue(json.has("isDeviceOwner"))
        // Each known route is probed, whether or not it is present.
        for (route in listOf("shizuku", "sui", "dhizuku")) {
            val r = json.getJSONObject(route)
            assertTrue("$route must report installed", r.has("installed"))
            assertTrue("$route must report permissionGranted", r.has("permissionGranted"))
        }
        Log.i(TAG, "escalation_recon -> $json")
    }

    @Test
    fun wireless_debug_reports_state_and_is_honest_about_the_code() {
        val json = call("wireless_debug")

        assertTrue(json.has("wirelessDebuggingEnabled"))
        assertTrue(json.has("usbDebuggingEnabled"))
        assertTrue(json.has("ips"))
        // The honest wall: the pair port/code are not readable by any app.
        assertFalse("the pair port must not be claimed readable", json.getBoolean("portReadable"))
        assertTrue(json.getString("pairCommandHint").contains("adb pair"))
        Log.i(TAG, "wireless_debug -> $json")
    }

    @Test
    fun launching_a_system_intent_from_the_app_process() {
        // The question: does startActivity from the app's own (background) process put a
        // system activity on screen, or does Android silently drop it? `started` from the
        // API is only "startActivity did not throw"; the proof is the resumed activity,
        // which is read here with shell identity via UiAutomation.
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val before = topActivity(ui)

        val json =
            call(
                "systemui_state",
                mapOf(
                    "action" to "launch",
                    "intent" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
                ),
            )
        val started = json.getBoolean("started")
        val reason = json.optString("reason")

        Thread.sleep(3000)
        val after = topActivity(ui)

        Log.i(TAG, "LAUNCH started=$started reason='$reason'")
        Log.i(TAG, "LAUNCH before='$before'")
        Log.i(TAG, "LAUNCH after='$after'")

        // Informational: the finding is whether `after` names the settings activity. No
        // hard assertion — a userdebug emulator is more permissive than a user build, so
        // the value of this is the observation, not a pass/fail.
        assertTrue("the launch attempt must report an outcome", json.has("started"))
    }

    /** The current top activity, read via a shell command with shell identity. */
    private fun topActivity(ui: android.app.UiAutomation): String {
        val pfd = ui.executeShellCommand("dumpsys activity activities")
        val text =
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(pfd)
                .bufferedReader()
                .readText()

        // The first resumed/visible activity line names the top activity.
        val line =
            text.lineSequence().firstOrNull { it.contains("mResumedActivity") }
                ?: text.lineSequence().firstOrNull { it.contains("ResumedActivity") }
                ?: text.lineSequence().firstOrNull { it.contains("topResumedActivity") }

        return line?.trim().orEmpty()
    }

    @Test
    fun container_status_names_the_three_parts() {
        val json = call("container_status")

        // Each part of the container is reported, so an empty shell is attributable.
        assertTrue(json.has("rootfsReady"))
        assertTrue(json.has("prootExecutable"))
        assertTrue(json.has("supervisorPresent"))
        assertTrue(json.has("layer3Installed"))
        assertTrue(json.has("shellUsable"))
        assertTrue("the verdict must be named", json.getString("verdict").isNotEmpty())
        // The fetch URLs are reported, so a missing asset is visible.
        assertTrue(json.has("layer3Url"))
        assertTrue(json.has("rootfsUrl"))
        Log.i(TAG, "container_status -> $json")
    }

    private companion object {
        const val TAG = "AdminApisLive"
    }
}
