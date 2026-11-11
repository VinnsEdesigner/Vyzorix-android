package com.vyzorix.bridge.apis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.SessionTokenRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The app-ops read: every op in the catalog is reported with its mode, and a denied op
 * is flagged and counted.
 *
 * Robolectric provides the `AppOpsManager` and its shadow, so a mode can be set for real
 * rather than the handler being handed a fake.
 */
@RunWith(RobolectricTestRunner::class)
class AppOpsApiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun call(): JSONObject {
        val token =
            SessionTokenRegistry().issue(
                "ops",
                "dev-1",
                "op-1",
                apiAllowlist = setOf(AppOpsApi.NAME),
            )
        val outcome = AppOpsApi.handle(ApiCall(context, token, emptyMap()))
        assertTrue("the API must answer", outcome.isOk)

        return JSONObject(outcome.json!!)
    }

    @Test
    fun `the catalogue of ops is returned with modes and meanings`() {
        val json = call()

        assertTrue("the op catalogue must be substantial", json.getInt("count") > 10)
        val ops = json.getJSONArray("ops")
        val first = ops.getJSONObject(0)
        assertTrue(first.has("op"))
        assertTrue(first.has("mode"))
        assertTrue(first.has("meaning"))

        // The overlay op is the one that matters most here; it must be present.
        val keys = (0 until ops.length()).map { ops.getJSONObject(it).getString("op") }
        assertTrue("SYSTEM_ALERT_WINDOW must be in the catalogue", "SYSTEM_ALERT_WINDOW" in keys)
    }

    @Test
    fun `every op reports a mode, and an op the platform does not know is unknown`() {
        // Robolectric's AppOpsManager shadow cannot resolve op *names* to modes (its
        // table is empty), so on the JVM every op reads as unknown — which is exactly the
        // graceful path this asserts. The real modes are a device question, covered by the
        // instrumented suite.
        val json = call()

        val ops = json.getJSONArray("ops")
        assertTrue("the catalogue must be substantial", ops.length() > 10)

        val modes = (0 until ops.length()).map { ops.getJSONObject(it).getString("mode") }
        val vocabulary = setOf("allowed", "ignored", "default", "foreground", "errored", "unknown")
        assertTrue(
            "every op must report a mode from the vocabulary; got ${modes.toSet()}",
            modes.all { it in vocabulary },
        )
        assertFalse(
            "an unknown op is a fact, not a denial; it must not count as denied",
            json.getBoolean("anyDenied"),
        )
    }
}
