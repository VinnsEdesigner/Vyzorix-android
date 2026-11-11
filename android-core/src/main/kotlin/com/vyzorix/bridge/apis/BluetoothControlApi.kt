// Lint's MissingPermission check cannot see the router's gate: ApiRouter.dispatch
// refuses a call whose API requires a runtime permission the app does not hold, and
// PermissionRegistry is the single source for which permission that is. Every call
// below is behind that gate, and the ones that also read the grant inline are checked
// again here. Suppressing the check at the file level is deliberate, not a way to
// hide an unchecked call.
@file:Suppress("MissingPermission")

package com.vyzorix.bridge.apis

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bluetooth control: turn the adapter on/off, discover nearby devices, pair, and list
 * what is bonded — plus the honest limits of what an app may do.
 *
 * ## What a normal app can and cannot do
 *
 *  - **On/off, discovery, pairing** are all reachable, each behind a runtime grant:
 *    `BLUETOOTH_CONNECT` (API 31+), `BLUETOOTH_SCAN` for discovery, and
 *    `BLUETOOTH_ADMIN` below 31 for on/off.
 *  - **Reading or writing HCI packets is not possible.** That needs the Bluetooth
 *    stack's HCI socket (`/dev/vhci` or a root-level raw socket), which a normal app
 *    cannot open. The API reports `packetAccess:false` with the reason rather than
 *    pretending — this is a kernel/privilege boundary, not a missing method.
 *
 * ## Why it is a class, not an object
 *
 * Discovery is an event: `startDiscovery` broadcasts `ACTION_FOUND` for each device
 * seen, and the results only exist while a receiver is registered. So [start] registers
 * the receiver and [stop] removes it, the same lifecycle `SmsReceivedApi` uses. The
 * service owns that lifecycle.
 *
 * A device's address is reported because pairing needs it; on API 31+ that needs
 * `BLUETOOTH_CONNECT`, which the router gates the whole API on.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_HARDWARE,
        ApiTags.OP_WRITE,
        ApiTags.PROTECTION_DANGEROUS,
        ApiTags.VISIBILITY_PRIVATE,
        ApiTags.HW_BLUETOOTH,
    ],
)
class BluetoothControlApi : ApiHandler {

    /** Devices seen since [start], keyed by address. */
    private val discovered = ConcurrentHashMap<String, JSONObject>()

    @Volatile private var scanning = false
    private var receiver: BroadcastReceiver? = null

    /** Registers the discovery receiver. Idempotent. */
    fun start(context: Context) {
        if (receiver != null) return

        val target = context.applicationContext
        val next =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> record(intent)
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                    }
                }
            }

        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }

        runCatching { target.registerReceiver(next, filter) }
            .onFailure { Log.d(TAG, "bluetooth receiver not registered: ${it.message}") }

        receiver = next
    }

    /** Removes the receiver. Safe to call when not started. */
    fun stop(context: Context) {
        val current = receiver ?: return

        runCatching { context.applicationContext.unregisterReceiver(current) }
        receiver = null
    }

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val manager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no bluetooth service")

        val adapter =
            manager.adapter
                ?: return ApiOutcome.ok(JSONObject().put("supported", false))

        val action = call.args["action"].orEmpty()

        // The router gates the API on BLUETOOTH_CONNECT, but the calls below are also
        // checked here so the refusal is explicit rather than a thrown SecurityException
        // — and so the read-only status path still works when the grant is missing.
        val canConnect = granted(context, Manifest.permission.BLUETOOTH_CONNECT)
        val canScan = granted(context, Manifest.permission.BLUETOOTH_SCAN)

        val result =
            JSONObject()
                .put("supported", true)
                .put("canConnect", canConnect)
                .put("canScan", canScan)
                .put("packetAccess", false)
                .put(
                    "packetAccessReason",
                    "reading/writing HCI packets needs the Bluetooth stack's HCI socket, " +
                        "which a normal app cannot open",
                )

        return try {
            if (canConnect) result.put("enabled", adapter.isEnabled)

            when (action) {
                "enable" -> {
                    // `enable()` is deprecated and blocked for targetSdk 33+, but this
                    // app pins targetSdk 28, so it still works. Reported honestly if not.
                    requirePermission(canConnect, "BLUETOOTH_CONNECT", result, "enable")
                    if (canConnect) {
                        val ok = adapter.enable()
                        result.put("enabled", ok).put("action", "enable")
                    }
                }
                "disable" -> {
                    requirePermission(canConnect, "BLUETOOTH_CONNECT", result, "disable")
                    if (canConnect) {
                        val ok = adapter.disable()
                        result
                            .put("enabled", adapter.isEnabled)
                            .put("action", "disable")
                            .put("disabled", ok)
                    }
                }
                "scan" -> {
                    requirePermission(canScan, "BLUETOOTH_SCAN", result, "scan")
                    if (canScan) startDiscovery(adapter, result)
                }
                "stopScan" -> {
                    requirePermission(canScan, "BLUETOOTH_SCAN", result, "stopScan")
                    if (canScan) {
                        adapter.cancelDiscovery()
                        scanning = false
                        result.put("scanning", false)
                    }
                }
                "pair" -> {
                    requirePermission(canConnect, "BLUETOOTH_CONNECT", result, "pair")
                    if (canConnect) pair(adapter, call.args["address"].orEmpty(), result)
                }
                else -> Unit
            }

            if (canConnect) result.put("bonded", bonded(adapter))
            result.put("discovered", JSONArray(discovered.values.toList()))
            result.put("scanning", scanning)

            ApiOutcome.ok(result)
        } catch (e: SecurityException) {
            ApiOutcome.ok(
                JSONObject()
                    .put("supported", true)
                    .put("known", false)
                    .put("reason", "a bluetooth permission is not granted: ${e.message}"),
            )
        }
    }

    /** Starts discovery and reports whether it began. */
    private fun startDiscovery(
        adapter: BluetoothAdapter,
        result: JSONObject,
    ) {
        discovered.clear()
        scanning = adapter.startDiscovery()
        result.put("scanning", scanning).put("action", "scan")
    }

    /** Pairs with the device at [address], reporting the outcome. */
    private fun pair(
        adapter: BluetoothAdapter,
        address: String,
        result: JSONObject,
    ) {
        result.put("action", "pair")

        if (address.isEmpty()) {
            result.put("paired", false).put("reason", "no address supplied")
            return
        }

        val device =
            try {
                adapter.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                result.put("paired", false).put("reason", "invalid address: ${e.message}")
                return
            }

        // `createBond` starts pairing; the user may be shown a PIN/consent dialog. The
        // call returns whether the *process* started, not whether pairing succeeded, so
        // the result says "initiated" and the current bond state.
        val initiated = device.createBond()
        result
            .put("pairInitiated", initiated)
            .put("bondState", device.bondState)
            .put("name", device.name.orEmpty())
    }

    /** The bonded (paired) devices. */
    private fun bonded(adapter: BluetoothAdapter): JSONArray {
        val array = JSONArray()

        for (device in adapter.bondedDevices.orEmpty()) {
            array.put(deviceJson(device))
        }

        return array
    }

    /** Records one discovered device. */
    private fun record(intent: Intent) {
        @Suppress("DEPRECATION")
        val device =
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

        discovered[device.address] = deviceJson(device)
    }

    /** A device as its address, name and bond state. */
    private fun deviceJson(device: BluetoothDevice): JSONObject =
        JSONObject()
            .put("address", device.address)
            .put("name", runCatching { device.name }.getOrNull().orEmpty())
            .put("bondState", device.bondState)
            .put("type", device.type)

    /** True when [permission] is granted. */
    private fun granted(
        context: Context,
        permission: String,
    ): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Records that [action] needs [permission] and it is not granted. */
    private fun requirePermission(
        granted: Boolean,
        permission: String,
        result: JSONObject,
        action: String,
    ) {
        if (!granted) {
            result
                .put(
                    "action",
                    action,
                ).put("ok", false)
                .put("reason", "$permission is not granted")
        }
    }

    companion object {
        const val NAME = "bluetooth_control"

        private const val TAG = "BluetoothControl"
    }
}
