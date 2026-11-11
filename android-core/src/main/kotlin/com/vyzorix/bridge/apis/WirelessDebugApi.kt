package com.vyzorix.bridge.apis

import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import java.net.Inet4Address
import java.net.NetworkInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wireless-debugging state, and what an operator needs to pair the container's `adb` to
 * this device over Wi-Fi.
 *
 * ## What is readable, and what is not
 *
 * `Settings.Global.adb_wifi_enabled` says whether **Wireless Debugging** is on, and the
 * device's own IPv4 addresses say what host to connect to. Both are readable here.
 *
 * The **pairing port** and the **6-digit pairing code** are *not*: the platform generates
 * them when the operator opens "Pair device with pairing code" and shows them only in that
 * Settings dialog. No public API exposes them, so this reports `portReadable:false` rather
 * than pretending — the operator reads the code off the screen and types it into the pair
 * command. (This is exactly what LADB does: it asks the user for the code.)
 *
 * ## The flow this feeds
 *
 *  1. the operator enables Developer options → Wireless Debugging;
 *  2. opens "Pair device with pairing code", which shows `<ip>:<pairPort>` and a code;
 *  3. in the container: `adb pair <ip>:<pairPort> <code>`;
 *  4. then `adb connect <ip>:<connectPort>` (the port on the Wireless Debugging screen).
 *
 * The container's `adbwireless` helper wraps steps 3–4. This API is the read half: is the
 * feature on, and what is the device's address.
 *
 * No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PRIVATE,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object WirelessDebugApi : ApiHandler {

    const val NAME = "wireless_debug"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val resolver = context.contentResolver

        val wirelessOn = globalOn(resolver, KEY_ADB_WIFI)
        val usbOn = globalOn(resolver, KEY_ADB)
        val ips = ipv4Addresses()

        return ApiOutcome.ok(
            JSONObject()
                .put("wirelessDebuggingEnabled", wirelessOn)
                .put("usbDebuggingEnabled", usbOn)
                .put("ips", ips)
                // The one address a user is most likely to type, or "" when there is none.
                .put("primaryIp", ips.optString(0, ""))
                // Honest about the wall: the pair port and code are shown only in the
                // Settings dialog and are not readable by any app.
                .put("portReadable", false)
                .put(
                    "note",
                    "the pairing port and code are shown only in the Settings dialog; " +
                        "read them there and pass them to `adb pair`",
                ).put("pairCommandHint", "adb pair <ip>:<pairPort> <pairingCode>")
                .put("connectCommandHint", "adb connect <ip>:<connectPort>"),
        )
    }

    private fun globalOn(
        resolver: android.content.ContentResolver,
        key: String,
    ): Boolean = Settings.Global.getInt(resolver, key, 0) != 0

    /** This device's IPv4 addresses, non-loopback first. Best-effort; never throws. */
    private fun ipv4Addresses(): JSONArray {
        val array = JSONArray()

        runCatching {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (address in iface.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        array.put(address.hostAddress.orEmpty())
                    }
                }
            }
        }

        return array
    }

    private const val KEY_ADB_WIFI = "adb_wifi_enabled"
    private const val KEY_ADB = "adb_enabled"
}
