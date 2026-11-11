package com.vyzorix.bridge.apis

import android.content.Context
import android.os.UserManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.SessionTokenRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The SystemUI-gating read: the QS tile layout is parsed, a set Secure/Global key is
 * reported with its value and presence, and the active restrictions are surfaced.
 *
 * Robolectric is the right level: `Settings.Secure`/`Global` are platform reads, and the
 * shadow lets a value be set for real rather than the handler being handed a fake.
 */
@RunWith(RobolectricTestRunner::class)
// ShadowUserManager.setUserRestriction(UserHandle, ...) is deprecated; the deprecated
// overload is the one that takes a key at run time.
@Suppress("DEPRECATION")
class SystemUiStateApiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun call(): JSONObject {
        val token =
            SessionTokenRegistry().issue(
                "sysui",
                "dev-1",
                "op-1",
                apiAllowlist = setOf(SystemUiStateApi.NAME),
            )
        val outcome = SystemUiStateApi.handle(ApiCall(context, token, emptyMap()))
        assertTrue("the API must answer", outcome.isOk)

        return JSONObject(outcome.json!!)
    }

    @Test
    fun `the QS tile layout is parsed into a list`() {
        Settings.Secure.putString(
            context.contentResolver,
            "sysui_qs_tiles",
            "wifi,cell,battery,dnd",
        )

        val json = call()
        val tiles = json.getJSONArray("qsTiles")

        assertEquals(4, tiles.length())
        assertEquals("wifi", tiles.getString(0))
        assertEquals("dnd", tiles.getString(3))
        assertEquals("wifi,cell,battery,dnd", json.getString("qsTilesRaw"))
    }

    @Test
    fun `an absent QS tile key reports empty rather than inventing a layout`() {
        val json = call()

        assertEquals(0, json.getJSONArray("qsTiles").length())
        assertEquals("", json.getString("qsTilesRaw"))
    }

    @Test
    fun `secure and global keys are reported with their values`() {
        Settings.Global.putString(context.contentResolver, "adb_enabled", "1")
        Settings.Secure.putString(context.contentResolver, "user_setup_complete", "1")

        val json = call()

        val secure = json.getJSONArray("secure")
        val userSetup =
            (0 until secure.length())
                .map { secure.getJSONObject(it) }
                .first { it.getString("key") == "user_setup_complete" }
        assertTrue("a set key must be present", userSetup.getBoolean("present"))
        assertEquals("1", userSetup.getString("value"))

        val global = json.getJSONArray("global")
        val adb =
            (0 until global.length())
                .map { global.getJSONObject(it) }
                .first { it.getString("key") == "adb_enabled" }
        assertEquals("1", adb.getString("value"))
    }

    @Test
    fun `an unset key is reported as absent, not as a value`() {
        val json = call()

        val global = json.getJSONArray("global")
        val provisioned =
            (0 until global.length())
                .map { global.getJSONObject(it) }
                .first { it.getString("key") == "device_provisioned" }

        // A key nothing has set reads as absent, so "SystemUI is using its default" is
        // distinguishable from "SystemUI is configured this way".
        assertTrue("an unset key must report present=false", !provisioned.getBoolean("present"))
        assertEquals("", provisioned.getString("value"))
    }

    @Test
    fun `active restrictions are listed so a hidden control is attributable`() {
        val users = context.getSystemService(Context.USER_SERVICE) as UserManager
        shadowOf(users).setUserRestriction(
            android.os.Process.myUserHandle(),
            UserManager.DISALLOW_CONFIG_TETHERING,
            true,
        )

        val json = call()
        val restrictions = json.getJSONArray("activeRestrictions")

        assertEquals(1, restrictions.length())
        assertEquals("no_config_tethering", restrictions.getString(0))
    }
}
