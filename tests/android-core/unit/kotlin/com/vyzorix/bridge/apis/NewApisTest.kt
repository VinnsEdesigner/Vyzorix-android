package com.vyzorix.bridge.apis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.SessionTokenRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three APIs that were named in `PermissionRegistry` before they existed:
 * `location`, `storage_all_files` and `sms_received`.
 *
 * Each is asserted on the property that makes it honest rather than on a value:
 *
 * - `location` must answer with a shape even when there is no cached fix, and must
 *   never invent a position. Robolectric has no providers, so "no fix" is the case
 *   under test here; the device suite covers the populated case.
 * - `storage_all_files` must distinguish "does not apply" (below API 30, where the
 *   permission does not exist) from "not granted" (API 30+, where it does). A false
 *   for both would tell a user nothing.
 * - `sms_received` must report `listening: false` for an instance that was never
 *   started, rather than a count of zero that reads as "no messages arrived".
 */
@RunWith(RobolectricTestRunner::class)
class NewApisTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun call(name: String): ApiCall {
        val token = SessionTokenRegistry().issue("d", "dev-1", "op-1", setOf(name))

        return ApiCall(context, token, emptyMap())
    }

    private fun jsonOf(name: String): JSONObject {
        val outcome =
            when (name) {
                LocationApi.NAME -> LocationApi.handle(call(name))
                StorageAllFilesApi.NAME -> StorageAllFilesApi.handle(call(name))
                PhoneStateDetailedApi.NAME -> PhoneStateDetailedApi.handle(call(name))
                else -> SmsReceivedApi().handle(call(name))
            }

        assertTrue("$name must succeed: ${outcome.error?.detail}", outcome.isOk)

        return JSONObject(outcome.json!!)
    }

    @Test
    fun `location reports a shape and never invents a fix`() {
        val json = jsonOf(LocationApi.NAME)

        assertTrue("location must report availability", json.has("available"))
        assertTrue("location must report whether a fix exists", json.has("hasFix"))

        // The load-bearing assertion: a fix is either present in full or absent
        // entirely. A half-populated fix (a latitude with no longitude) would be
        // worse than none, because a caller would use it.
        if (json.getBoolean("hasFix")) {
            assertTrue(json.has("latitude"))
            assertTrue(json.has("longitude"))
            assertTrue(json.has("provider"))
        } else {
            assertFalse("no fix means no coordinates", json.has("latitude"))
            assertFalse(json.has("longitude"))
        }
    }

    @Test
    @Config(sdk = [29])
    fun `storage_all_files says the permission does not apply below api 30`() {
        // MANAGE_EXTERNAL_STORAGE arrived with scoped storage in API 30. On 29 the
        // honest answer is "not applicable", not "denied": a device on 29 does not
        // need it, and reporting a false would look like a missing grant.
        val json = jsonOf(StorageAllFilesApi.NAME)

        assertFalse(json.getBoolean("applicable"))
        assertFalse(json.getBoolean("granted"))
        assertTrue(json.has("reason"))
    }

    @Test
    @Config(sdk = [33])
    fun `storage_all_files reports applicability and the granting screen on api 30 plus`() {
        val json = jsonOf(StorageAllFilesApi.NAME)

        assertTrue("the permission exists from API 30", json.getBoolean("applicable"))
        assertTrue("the grant state must be reported", json.has("granted"))
        // The screen is part of the answer: there is no dialog for this permission,
        // so a caller that knows the state and not the screen cannot act on it.
        assertTrue(json.getString("settingsAction").isNotEmpty())
    }

    @Test
    fun `sms_received reports not-listening rather than an empty count`() {
        // A receiver that was never started has seen nothing, but "0 messages" and
        // "not watching" are different facts. The API must say which.
        val json = jsonOf(SmsReceivedApi.NAME)

        assertFalse("a fresh instance is not listening", json.getBoolean("listening"))
        assertTrue(json.has("count"))
        assertFalse("no message means no sender", json.getBoolean("hasMessage"))
        assertFalse(json.has("lastSender"))
    }

    @Test
    fun `sms_received reports the raw sender, not a hash`() {
        // The sender is the originating address verbatim: a user whitelisting a
        // paging number or telling a service sender from a person cannot work on a
        // hash. The message *body* is still never reported, which is the line that
        // matters — the sender answers "who", the body would be the content.
        val json = jsonOf(SmsReceivedApi.NAME)

        // The field is absent until a message arrives, and when present it is the
        // address string. Asserted on the source so a future change to a hash is a
        // visible test failure rather than a silent behaviour change.
        val source = File(apiSource("SmsReceivedApi.kt")).readText()
        assertTrue(
            "the sender must be stored as the raw address",
            source.contains("originatingAddress"),
        )
        assertFalse(
            "the sender must not be hashed",
            source.contains("senderHash"),
        )
        assertFalse(
            "the message body must never be reported",
            source.contains("messageBody") || source.contains("getMessageBody"),
        )
        // Present-but-absent semantics: no message, no field.
        assertFalse(json.has("lastSender"))
    }

    @Test
    fun `phone_state_detailed reports the READ_PHONE_STATE fields`() {
        val json = jsonOf(PhoneStateDetailedApi.NAME)

        val fields =
            listOf("softwareVersion", "dataNetworkType", "voiceNetworkType", "isNetworkRoaming")
        for (field in fields) {
            assertTrue("phone_state_detailed must carry $field", json.has(field))
        }

        // Network types are named, not left as bare constants: a reader cannot use an
        // integer, and `unknown` must be distinguishable from a real type.
        for (field in listOf("dataNetworkType", "voiceNetworkType")) {
            assertTrue(
                "$field must be a name, not an empty string",
                json.getString(field).isNotEmpty(),
            )
        }
    }

    @Test
    fun `phone_state_detailed never reports subscriber identifiers`() {
        // IMEI, IMSI and the phone number need READ_PRIVILEGED_PHONE_STATE and are the
        // most sensitive values on the device. The API must not read them even where a
        // grant would allow it, so the assertion is on the source.
        val source = File(apiSource("PhoneStateDetailedApi.kt")).readText()

        val forbidden =
            listOf(
                "getImei",
                "getMeid",
                "getSubscriberId",
                "getSimSerialNumber",
                "getLine1Number",
            )
        for (method in forbidden) {
            assertFalse(
                "phone_state_detailed must not call $method",
                source.contains(method),
            )
        }
    }

    /**
     * A handler's own source, by file name.
     *
     * Read to assert *how* a handler works where a JSON result cannot show it — both
     * a hash and an address are "a string", and a method that is simply not called
     * leaves no trace in the output. The path is resolved from the repository root
     * rather than the test's working directory, which is the module, not the root —
     * the same walk `ApiCatalogTest` uses.
     */
    private fun apiSource(fileName: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) {
                val relative = "android-core/src/main/kotlin/com/vyzorix/bridge/apis/$fileName"

                return File(dir, relative).path
            }
            dir = dir.parentFile
        }

        throw AssertionError("could not find the repository root from ${File("").absolutePath}")
    }
}
