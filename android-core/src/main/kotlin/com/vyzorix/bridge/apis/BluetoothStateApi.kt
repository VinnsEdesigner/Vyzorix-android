package com.vyzorix.bridge.apis

import android.bluetooth.BluetoothManager
import android.content.Context
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Bluetooth adapter state. `BLUETOOTH` is a normal permission granted at install.
 * From Android 12 the adapter *name* needs BLUETOOTH_CONNECT, so the name is not
 * reported — the state is, which is what an operator needs.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object BluetoothStateApi : ApiHandler {

    const val NAME = "bluetooth_state"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no bluetooth service",
                )

        val adapter =
            manager.adapter
                ?: return ApiOutcome.ok(JSONObject().put("supported", false).put("enabled", false))

        // `isEnabled()` needs BLUETOOTH_CONNECT from API 31, where BLUETOOTH was
        // split. The router gates this API on BLUETOOTH (the permission that exists
        // across the whole range), so on a 31+ device without the CONNECT grant the
        // call throws rather than the API failing closed. Caught and reported as
        // unknown, which is honest: the device may well be enabled, we just cannot
        // see it, and saying "off" would be a wrong answer rather than a missing one.
        val enabled =
            try {
                adapter.isEnabled
            } catch (e: SecurityException) {
                return ApiOutcome.ok(
                    JSONObject()
                        .put("supported", true)
                        .put("enabled", false)
                        .put("known", false)
                        .put("reason", "BLUETOOTH_CONNECT not granted: ${e.message}"),
                )
            }

        return ApiOutcome.ok(
            JSONObject()
                .put("supported", true)
                .put("enabled", enabled)
                .put("known", true),
        )
    }
}
