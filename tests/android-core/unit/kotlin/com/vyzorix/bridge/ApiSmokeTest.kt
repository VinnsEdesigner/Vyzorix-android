package com.vyzorix.bridge

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.bridge.apis.SmsReceivedApi
import com.vyzorix.bridge.apis.UsbAttachApi
import com.vyzorix.bridge.apis.UsbDetachApi
import com.vyzorix.bridge.apis.UsbStateApi
import com.vyzorix.capability.usb.UsbBroker
import com.vyzorix.capability.usb.UsbDeviceInfo
import com.vyzorix.capability.usb.UsbHost
import com.vyzorix.permissions.PermissionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every registered API, called for real.
 *
 * ## Why this test exists
 *
 * A catalogue of fifty handlers has one failure mode that no amount of reading
 * catches: a handler that **throws** on a real device because it asked for a system
 * service that is not there, read a `/proc` file that is not readable, or called an
 * API level the device does not have. The router turns that into an `internal` error
 * response, so it is not a crash — it is a command that never works, and nothing
 * tells you which one until an operator tries it.
 *
 * So every API in [ApiCatalogue] is dispatched here. The assertion is deliberately
 * weak on the *result* — most of these read real device state, and the value is not
 * this test's business — and strict on one thing: the call must produce an outcome
 * rather than an internal error. That is exactly the difference between "this API
 * works" and "this API is broken in a way only a user would find".
 *
 * ## What it runs against
 *
 * Robolectric, which supplies a real Android runtime and its system services. It is
 * not a device: some values will be the framework's defaults, and a `/proc` file that
 * a device has may be absent. That is the point — the test is checking the handler's
 * *shape*, not the device's state. The emulator suite covers the device.
 */
@RunWith(RobolectricTestRunner::class)
class ApiSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A token scoped to every API, so the scope check is never what fails. */
    private fun token(): SessionToken =
        SessionTokenRegistry().issue(
            "smoke",
            "dev-1",
            "op-1",
            apiAllowlist = ApiCatalogue.names.toSet(),
        )

    /**
     * A router with the whole catalogue and no permission requester.
     *
     * No requester means an API needing a runtime permission is refused — which is
     * correct, and is asserted separately below rather than being mistaken for a
     * failure here.
     */
    private fun router(): ApiRouter {
        // The service registers the USB trio with a real broker; here a fake host
        // with no devices drives them, so the smoke test exercises the handler path
        // (argument parsing, result shaping) without hardware. The service's own
        // registration is what `ApiCatalogTest` checks against `apis.txt`.
        val usb = UsbBroker(NoDevicesUsbHost)

        return ApiCatalogue
            .registerAll(ApiRouter())
            .register(UsbStateApi.NAME, UsbStateApi(usb))
            .register(UsbAttachApi.NAME, UsbAttachApi(usb))
            .register(UsbDetachApi.NAME, UsbDetachApi(usb))
            // `sms_received` is service-registered too: it owns a receiver with a
            // start/stop lifecycle. A fresh instance here reports `listening: false`,
            // which is the correct answer for a receiver that was never started.
            .register(SmsReceivedApi.NAME, SmsReceivedApi())
    }

    /** A USB host with nothing attached, so the handlers answer without hardware. */
    private object NoDevicesUsbHost : UsbHost {
        override fun devices(): List<UsbDeviceInfo> = emptyList()

        override fun open(name: String): Int = -1

        override fun close(name: String) = Unit
    }

    /**
     * The APIs the router refuses without a permission requester.
     *
     * Derived from the registry rather than listed, so it cannot drift: an API whose
     * entry is added or removed moves in or out of this set automatically, and the
     * two tests below then check the *rule* — gated APIs are refused, ungated ones are
     * not — rather than a hand-kept list that would go stale.
     *
     * `logcat` is here because READ_LOGS is signature-only; `network` because
     * ACCESS_NETWORK_STATE is a normal permission, which still has to be *declared*
     * and is therefore still checked.
     */
    private val permissionGated =
        ApiCatalogue.names.filter {
            PermissionRegistry.requiresPermissionOn(it, Build.VERSION.SDK_INT)
        }

    @Test
    fun `every api answers rather than reporting an internal error`() {
        val router = router()
        val failures = mutableListOf<String>()

        for (name in ApiCatalogue.names) {
            // `session` needs the service's session table, so it is registered with
            // callbacks and is covered by the service's own suite instead.
            if (name == ApiCatalogue.SESSION_API_NAME) continue

            val outcome = router.dispatch(context, token(), name, argsFor(name))

            val internal = outcome.error?.kind == BridgeErrorKind.INTERNAL
            if (internal) {
                failures += "$name -> ${outcome.error?.detail}"
            }
        }

        assertTrue(
            "these APIs failed with an internal error, which means the handler threw " +
                "rather than reading a fact:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `every api in the catalogue is registered and dispatchable`() {
        val router = router()

        for (name in ApiCatalogue.names) {
            if (name == ApiCatalogue.SESSION_API_NAME) continue

            val outcome = router.dispatch(context, token(), name, argsFor(name))

            // `unknown_api` would mean the catalogue lists a handler the router never
            // received, which is the failure `registerAll` exists to make impossible.
            assertTrue(
                "$name is in the catalogue but the router does not serve it: " +
                    "${outcome.error?.detail}",
                outcome.error?.kind != BridgeErrorKind.UNKNOWN_API,
            )
        }
    }

    @Test
    fun `a permission-gated api is refused without a requester`() {
        // Fail-closed: with no PermissionRequester the router cannot know whether the
        // permission is held, so it refuses. The alternative — assuming granted —
        // would let a call reach a platform API that then throws SecurityException.
        val router = router()

        for (name in permissionGated) {
            val outcome = router.dispatch(context, token(), name, emptyMap())

            assertEquals(
                "$name needs a runtime permission and must be refused without a requester",
                BridgeErrorKind.FORBIDDEN,
                outcome.error?.kind,
            )
        }
    }

    @Test
    fun `an api that needs no permission is not refused`() {
        // The other half of the rule above: a refusal here would mean the permission
        // registry has an entry for an API that does not need one, which would make
        // the API permanently uncallable on a device that never grants it.
        val router = router()
        val refused = mutableListOf<String>()

        for (name in ApiCatalogue.names) {
            if (name == ApiCatalogue.SESSION_API_NAME || name in permissionGated) continue

            val outcome = router.dispatch(context, token(), name, argsFor(name))
            if (outcome.error?.kind == BridgeErrorKind.FORBIDDEN) {
                refused += "$name -> ${outcome.error?.detail}"
            }
        }

        assertTrue(
            "these APIs were refused for a permission but need none:\n" +
                refused.joinToString("\n"),
            refused.isEmpty(),
        )
    }

    @Test
    fun `every api result is valid json`() {
        // The result crosses the wire as JSON, so a handler building a malformed
        // string would fail at the client rather than here. `org.json` is what the
        // handlers build with, so this catches an unbalanced object or a NaN.
        val router = router()
        val broken = mutableListOf<String>()

        for (name in ApiCatalogue.names) {
            if (name == ApiCatalogue.SESSION_API_NAME || name in permissionGated) continue

            val outcome = router.dispatch(context, token(), name, argsFor(name))
            val json = outcome.json ?: continue

            val parsed = runCatching { org.json.JSONObject(json) }
            if (parsed.isFailure) {
                broken += "$name -> ${json.take(120)}"
            }
        }

        assertTrue(
            "these APIs returned malformed JSON:\n" + broken.joinToString("\n"),
            broken.isEmpty(),
        )
    }

    /**
     * The arguments an API needs to be callable at all.
     *
     * `package_info` is the only API that requires one, so it is the only entry. An
     * API added later that needs an argument will fail the smoke test loudly, which
     * is the intent: the requirement is then stated here rather than discovered by
     * an operator.
     */
    private fun argsFor(api: String): Map<String, String> =
        when (api) {
            "package_info" -> mapOf("package" to context.packageName)
            // The USB write pair needs a device name; with no devices the name is
            // still required, and this drives the real handler rather than the
            // missing-argument branch.
            "usb_attach", "usb_detach" -> mapOf("name" to "1-1")
            else -> emptyMap()
        }

    @Test
    fun `the usb write pair refuses a missing device name`() {
        // Both USB write APIs require a `name`. Without it they must answer a clean
        // `invalid_args` refusal rather than reaching the broker — a missing argument
        // is a caller mistake, not an internal failure, and the E2E sweep calls every
        // API with no arguments, so this is the exact path it exercises.
        val router = router()

        for (api in listOf("usb_attach", "usb_detach")) {
            val outcome = router.dispatch(context, token(), api, emptyMap())

            assertEquals(
                "$api must refuse a missing name with invalid_args",
                BridgeErrorKind.INVALID_ARGS,
                outcome.error?.kind,
            )
        }
    }
}
