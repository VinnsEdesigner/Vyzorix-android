package com.vyzorix.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is the single source of truth for permission requirements, so its
 * invariants are pinned here: an unknown API asks for nothing, the Build 2 five
 * stay permission-free, and a Settings-only permission never claims to be
 * dialog-grantable.
 */
class PermissionRegistryTest {

    @Test
    fun `the build 2 five need no runtime permission`() {
        // These were chosen so the bridge stayed testable from adb without a UI.
        // If one starts needing a permission, the bridge is no longer headless.
        val five = listOf("battery", "device_info", "storage", "session")

        for (api in five) {
            assertTrue(
                "$api must need no runtime permission",
                PermissionRegistry.needsNoPermission(api),
            )
        }
    }

    @Test
    fun `an unknown api asks for nothing`() {
        // Fail-closed in the same shape as the server catalog: no entry means no
        // requirement, and the handler decides for itself.
        assertNull(PermissionRegistry.forApi("does_not_exist"))
        assertTrue(PermissionRegistry.needsNoPermission("does_not_exist"))
    }

    @Test
    fun `a dangerous api is classified dangerous`() {
        assertEquals(Protection.DANGEROUS, PermissionRegistry.forApi("camera")?.protection)
        assertEquals(Protection.DANGEROUS, PermissionRegistry.forApi("location")?.protection)
    }

    @Test
    fun `a settings-only permission carries its action`() {
        val allFiles = PermissionRegistry.forApi("storage_all_files")
        assertTrue("all-files access is granted through Settings", allFiles?.settingsAction != null)

        val battery = PermissionRegistry.forApi("battery_optimization")
        assertTrue("battery exemption is granted through Settings", battery?.settingsAction != null)
    }

    @Test
    fun `a permission with a minSdk above one is not requested below it`() {
        // POST_NOTIFICATIONS exists from API 33. On an older device the request is
        // silently ignored, which would look like a denial.
        val notifications = PermissionRegistry.forApi("notification")
        assertEquals(33, notifications?.minSdk)
    }

    @Test
    fun `every entry with a permission names a real android permission`() {
        for (name in PermissionRegistry.names()) {
            val def = PermissionRegistry.forApi(name) ?: continue
            if (def.androidPermission.isEmpty()) continue
            assertTrue(
                "$name must name a fully-qualified permission, got ${def.androidPermission}",
                def.androidPermission.startsWith("android.permission."),
            )
        }
    }

    @Test
    fun `every entry with a settings action names an intent action`() {
        for (name in PermissionRegistry.names()) {
            val def = PermissionRegistry.forApi(name) ?: continue
            val action = def.settingsAction ?: continue
            assertTrue(
                "$name settingsAction must be an intent action, got $action",
                action.startsWith("android.settings."),
            )
        }
    }

    @Test
    fun `a signature permission is never treated as dialog-grantable`() {
        // READ_LOGS is signature-level: no dialog can grant it, so the requester
        // must not pretend it can.
        val logcat = PermissionRegistry.forApi("logcat")
        assertEquals(Protection.SIGNATURE, logcat?.protection)
        assertNull("a signature permission has no settings shortcut", logcat?.settingsAction)
    }

    @Test
    fun `requested permissions are empty for an api that needs none`() {
        assertTrue(PermissionRequester.requestedPermissions("battery").isEmpty())
        assertTrue(PermissionRequester.requestedPermissions("unknown").isEmpty())
    }

    @Test
    fun `allGranted requires every result to be granted`() {
        val perms = arrayOf("android.permission.CAMERA")

        assertTrue(PermissionRequester.allGranted(perms, intArrayOf(0)))
        assertFalse(PermissionRequester.allGranted(perms, intArrayOf(-1)))
        assertTrue(PermissionRequester.allGranted(emptyArray(), intArrayOf()))
        assertFalse(
            "a size mismatch must not read as granted",
            PermissionRequester.allGranted(perms, intArrayOf()),
        )
    }

    @Test
    fun `needsNoPermission is false only when a permission is required`() {
        assertFalse(PermissionRegistry.needsNoPermission("camera"))
        assertTrue(PermissionRegistry.needsNoPermission("battery"))
    }

    @Test
    fun `dialog grantable permissions include the api gated dangerous ones`() {
        // The bug this guards: a dangerous permission declared in the manifest but
        // absent from the requested set is granted to nobody, so every API gated on
        // it returns FORBIDDEN forever. `call_capability` is deliberately NOT here —
        // it reports CALL_PHONE's state rather than dialing, so it needs no grant and
        // is not gated (see its handler).
        val requested = PermissionRegistry.dialogGrantablePermissions(33)

        for (api in listOf("camera", "location", "phone_state_detailed", "sms_read")) {
            val permission = PermissionRegistry.forApi(api)?.androidPermission
            assertTrue(
                "$api is dangerous, so $permission must be requested",
                permission != null && permission in requested,
            )
        }
    }

    @Test
    fun `dialog grantable permissions exclude settings only grants`() {
        // requestPermissions cannot grant these; listing them would show a dialog
        // that does nothing. Their handlers open the Settings screen instead.
        val requested = PermissionRegistry.dialogGrantablePermissions(33)

        assertFalse(
            "all-files access is Settings-only",
            "android.permission.MANAGE_EXTERNAL_STORAGE" in requested,
        )
        assertFalse(
            "battery exemption is Settings-only",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in requested,
        )
    }

    @Test
    fun `dialog grantable permissions respect minSdk`() {
        // POST_NOTIFICATIONS exists from 33; requesting it below would be silently
        // ignored and read as a denial.
        val at32 = PermissionRegistry.dialogGrantablePermissions(32)
        val at33 = PermissionRegistry.dialogGrantablePermissions(33)

        assertFalse(
            "POST_NOTIFICATIONS must not be requested below 33",
            "android.permission.POST_NOTIFICATIONS" in at32,
        )
        assertTrue(
            "POST_NOTIFICATIONS must be requested at 33",
            "android.permission.POST_NOTIFICATIONS" in at33,
        )
    }

    @Test
    fun `dialog grantable permissions are distinct and sorted`() {
        val requested = PermissionRegistry.dialogGrantablePermissions(33)

        assertEquals(
            "the set must not repeat a permission",
            requested.size,
            requested.toSet().size,
        )
        assertEquals(
            "the set must be sorted for a stable dialog order",
            requested.sorted(),
            requested,
        )
    }
}
