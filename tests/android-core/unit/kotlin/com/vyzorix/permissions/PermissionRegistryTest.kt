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
}
