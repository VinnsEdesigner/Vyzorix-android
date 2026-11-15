package com.vyzorix.bridge.apis

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.SessionTokenRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `device_info` is the "what device is this" summary, so its shape is the contract:
 * a field a user relies on must be present, and a field that only exists on a
 * newer API must be present-but-empty rather than missing or a crash.
 *
 * The SoC fields are the reason this test exists at all: `Build.SOC_MODEL` is API 31,
 * and this module compiles against SDK 34 with a minSdk of 26, so an unguarded read
 * compiles cleanly and then throws `NoSuchFieldError` on a 26-30 device. The test
 * pins both sides of the guard.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceInfoApiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun result(): JSONObject {
        val token = SessionTokenRegistry().issue("d1", "dev-1", "op-1", setOf(DeviceInfoApi.NAME))
        val outcome = DeviceInfoApi.handle(ApiCall(context, token, emptyMap()))
        assertTrue("device_info must succeed: ${outcome.error?.detail}", outcome.isOk)

        return JSONObject(outcome.json!!)
    }

    @Test
    fun `reports the platform and hardware identity`() {
        val json = result()

        for (field in PLATFORM_AND_HARDWARE_FIELDS) {
            assertTrue("device_info must carry $field", json.has(field))
        }

        // The platform facts must be the real ones, not defaults: this is the whole
        // point of the API, so a hardcoded value would be worse than absent.
        assertEquals(Build.MODEL, json.getString("model"))
        assertEquals(Build.MANUFACTURER, json.getString("manufacturer"))
        assertEquals(Build.VERSION.SDK_INT, json.getInt("apiLevel"))
    }

    @Test
    fun `splits the abi lists`() {
        val json = result()

        assertEquals(Build.SUPPORTED_ABIS.joinToString(","), json.getString("supportedAbis"))
        assertEquals(
            Build.SUPPORTED_32_BIT_ABIS.joinToString(","),
            json.getString("supported32BitAbis"),
        )
        assertEquals(
            Build.SUPPORTED_64_BIT_ABIS.joinToString(","),
            json.getString("supported64BitAbis"),
        )
    }

    @Test
    @Config(sdk = [30])
    fun `soc fields are present but empty below api 31`() {
        // A 26-30 device must answer, not throw: the fields exist so a caller can read
        // them unconditionally, and empty is the honest value.
        val json = result()

        assertTrue(json.has("socManufacturer"))
        assertTrue(json.has("socModel"))
        assertEquals("", json.getString("socManufacturer"))
        assertEquals("", json.getString("socModel"))
    }

    @Test
    @Config(sdk = [33])
    fun `soc fields are read on api 31 and above`() {
        val json = result()

        assertTrue(json.has("socManufacturer"))
        assertTrue(json.has("socModel"))
    }

    private companion object {
        /** Fields that must be present on every device, regardless of API level. */
        val PLATFORM_AND_HARDWARE_FIELDS =
            listOf(
                "model",
                "manufacturer",
                "androidVersion",
                "apiLevel",
                "codename",
                "incremental",
                "securityPatch",
                "device",
                "product",
                "brand",
                "board",
                "hardware",
                "bootloader",
                "buildHost",
                "supportedAbis",
                "supported32BitAbis",
                "supported64BitAbis",
                "uptimeMs",
                "packageName",
                "socManufacturer",
                "socModel",
            )
    }
}
