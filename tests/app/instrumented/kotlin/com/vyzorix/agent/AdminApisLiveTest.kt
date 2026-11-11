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

        // Each package carries its name, its exact folders, its family, and its
        // children (APK splits).
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
        )
        ) {
            assertTrue("the dump must carry $key", first.has(key))
        }

        // The shared-uid groups are the parent/children view.
        assertTrue(json.has("sharedUidGroups"))
    }

    private companion object {
        const val TAG = "AdminApisLive"
    }
}
