package com.vyzorix.bridge.apis

import android.content.Context
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.SessionTokenRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The user-restrictions dump: every AOSP restriction is listed, the active ones are
 * flagged, and a restriction the catalog does not name is still surfaced.
 *
 * Robolectric is the right level here: `UserManager.userRestrictions` is a platform read,
 * and the shadow lets a restriction be set for real rather than the handler being fed a
 * fake bundle.
 */
@RunWith(RobolectricTestRunner::class)
// ShadowUserManager.setUserRestriction(UserHandle, ...) is deprecated in favour of a
// builder; the deprecated overload is the one that takes a key at run time.
@Suppress("DEPRECATION")
class UserRestrictionsApiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Marks [key] restricted on the current user, through the platform shadow. */
    private fun restrict(key: String) {
        val users = context.getSystemService(Context.USER_SERVICE) as UserManager
        shadowOf(users).setUserRestriction(Process.myUserHandle(), key, true)
    }

    private fun call(args: Map<String, String> = emptyMap()): JSONObject {
        val token =
            SessionTokenRegistry().issue(
                "restrictions",
                "dev-1",
                "op-1",
                apiAllowlist = setOf(UserRestrictionsApi.NAME),
            )
        val outcome = UserRestrictionsApi.handle(ApiCall(context, token, args))
        assertTrue("the API must answer", outcome.isOk)

        return JSONObject(outcome.json!!)
    }

    @Test
    fun `the full AOSP catalogue is returned, restricted or not`() {
        val json = call()

        // The point of the API: a caller learns about restrictions it did not ask for,
        // so the whole vocabulary is present, not just the active set.
        assertTrue("the catalogue must be substantial", json.getInt("catalogCount") > 40)

        val restrictions = json.getJSONArray("restrictions")
        assertEquals(json.getInt("catalogCount"), restrictions.length())

        // Every entry names a key and says what it blocks.
        val first = restrictions.getJSONObject(0)
        assertTrue(first.has("key"))
        assertTrue(first.has("meaning"))
        assertTrue(first.has("restricted"))

        // A known restriction is present whether or not it is active.
        val keys =
            (0 until restrictions.length()).map { index ->
                restrictions.getJSONObject(index).getString("key")
            }
        assertTrue("no_usb_file_transfer must be in the catalogue", "no_usb_file_transfer" in keys)
        assertTrue(
            "no_debugging_features must be in the catalogue",
            "no_debugging_features" in keys,
        )
    }

    @Test
    fun `an active restriction is flagged and counted`() {
        restrict(UserManager.DISALLOW_USB_FILE_TRANSFER)

        val json = call()

        assertTrue("a restricted device must say so", json.getBoolean("anyRestricted"))
        assertEquals(1, json.getInt("restrictedCount"))
        assertTrue(json.getJSONObject("active").has("no_usb_file_transfer"))

        val restrictions = json.getJSONArray("restrictions")
        val usb =
            (0 until restrictions.length())
                .map { restrictions.getJSONObject(it) }
                .first { it.getString("key") == "no_usb_file_transfer" }
        assertTrue("the active restriction must be marked", usb.getBoolean("restricted"))
    }

    @Test
    fun `restricted entries are listed first`() {
        restrict(UserManager.DISALLOW_FACTORY_RESET)

        val json = call()
        val first = json.getJSONArray("restrictions").getJSONObject(0)

        assertTrue(
            "the head of the list must be a restricted feature",
            first.getBoolean("restricted"),
        )
        assertEquals("no_factory_reset", first.getString("key"))
    }

    @Test
    fun `a restriction the catalogue does not name is still surfaced`() {
        // An OEM-only key the AOSP vocabulary does not list.
        restrict("no_oem_special_sauce")

        val json = call()

        val unknown = json.getJSONArray("unknown")
        assertEquals(1, unknown.length())
        assertEquals("no_oem_special_sauce", unknown.getString(0))
        // It must still be counted as restricted, so nothing active is ever hidden.
        assertTrue(json.getBoolean("anyRestricted"))
    }

    @Test
    fun `a device with no restrictions reports none`() {
        val json = call()

        assertFalse("a clean device must not claim restrictions", json.getBoolean("anyRestricted"))
        assertEquals(0, json.getInt("restrictedCount"))
        assertEquals(0, json.getJSONArray("unknown").length())
    }
}
