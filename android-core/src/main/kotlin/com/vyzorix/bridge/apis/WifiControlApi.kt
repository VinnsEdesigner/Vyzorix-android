// Lint's MissingPermission check cannot see the router's gate: ApiRouter.dispatch
// refuses a call whose API requires a runtime permission the app does not hold, and
// PermissionRegistry is the single source for which permission that is. Every call
// below is behind that gate, and the ones that also read the grant inline are checked
// again here. Suppressing the check at the file level is deliberate, not a way to
// hide an unchecked call.
@file:Suppress("MissingPermission")

package com.vyzorix.bridge.apis

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.UserManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.service.DeviceSettingsWriter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wi-Fi control: turn the radio on/off, scan, connect to a network, disconnect, and
 * list what is configured and what is visible.
 *
 * ## Why the legacy API is used here
 *
 * `setWifiEnabled`, `addNetwork` and `getConfiguredNetworks` are deprecated and, from
 * `targetSdk` 29, silently refuse. This app pins `targetSdk` 28 (for the container's
 * `execve`, see the README), so they still work — the same reason the terminal does.
 * That is a deliberate consequence of the targetSdk pin, not an oversight.
 *
 * ## What a normal app can and cannot do
 *
 *  - **on/off, scan, connect, disconnect** — all reachable with `CHANGE_WIFI_STATE`
 *    (on/off, connect) and `ACCESS_FINE_LOCATION` (scan results), both of which the
 *    router gates this API on.
 *  - **Enabling a hotspot (SoftAP), USB tethering or Bluetooth tethering is not
 *    possible** from a normal app. `setWifiApEnabled` and `TetheringManager.startTethering`
 *    are `@SystemApi`; there is no public method. The API reports
 *    `hotspotSupported:false` with the reason rather than pretending — this is a
 *    platform boundary, not a missing call.
 *
 * ## Why tethering is reported, not just refused
 *
 * There are two independent reasons tethering may be unavailable, and they need
 * different answers, so both are reported:
 *
 *  - **A policy restriction.** `no_config_tethering` (all tethering, including the
 *    Settings UI) or `no_wifi_tethering` (Wi-Fi only) may be set. These are the
 *    `UserManager` restrictions a device owner or the OEM applies. They are read here
 *    and named, so "the hotspot toggle is greyed out" is attributable to a specific
 *    restriction rather than left a mystery. `canUnblockTethering` says whether *this*
 *    app could clear it (owner only).
 *  - **The platform boundary.** Even with no restriction, no public API starts a
 *    hotspot. That is `hotspotReason`, and it stands regardless of the policy.
 *
 * Clearing the restriction is deliberately not done here: it is a policy mutation, and
 * it would not make the hotspot start anyway (the @SystemApi wall remains), so the
 * honest scope for this API is to report both.
 *
 * ## Connecting
 *
 * `connect` takes an `ssid` and an optional `password`. A network with no password is
 * treated as open (key management `NONE`); one with a password as WPA-PSK. The
 * configured network is added, enabled and selected. An already-configured network is
 * matched by SSID and re-used rather than duplicated.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_HARDWARE,
        ApiTags.OP_WRITE,
        ApiTags.PROTECTION_DANGEROUS,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object WifiControlApi : ApiHandler {

    const val NAME = "wifi_control"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val wifi =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no wifi service")

        val result =
            JSONObject()
                .put("enabled", wifi.isWifiEnabled)
                .put("state", wifi.wifiState)
                // The two reasons tethering may be unavailable: a policy restriction
                // (named), and the @SystemApi ceiling that stands regardless. Both are
                // reported so "the hotspot toggle is greyed out" has an answer.
                .put("hotspotSupported", false)
                .put(
                    "hotspotReason",
                    "enabling a hotspot or tethering needs @SystemApi " +
                        "(setWifiApEnabled / TetheringManager), which a normal app cannot call",
                ).put("tetheringRestrictions", tetheringRestrictions(context))

        val action = call.args["action"].orEmpty()

        return try {
            when (action) {
                "enable" -> result.put("enabled", wifi.setWifiEnabled(true)).put("action", "enable")
                "disable" ->
                    result
                        .put(
                            "enabled",
                            wifi.setWifiEnabled(false),
                        ).put("action", "disable")
                "scan" -> result.put("scanStarted", wifi.startScan()).put("action", "scan")
                "connect" ->
                    connect(
                        wifi,
                        call.args["ssid"].orEmpty(),
                        call.args["password"].orEmpty(),
                        result,
                    )
                "disconnect" ->
                    result
                        .put(
                            "disconnected",
                            wifi.disconnect(),
                        ).put("action", "disconnect")
                else -> Unit
            }

            result.put("scanResults", scanResults(wifi))
            result.put("configured", configured(wifi))

            ApiOutcome.ok(result)
        } catch (e: SecurityException) {
            ApiOutcome.ok(
                JSONObject()
                    .put("known", false)
                    .put("reason", "a wifi permission is not granted: ${e.message}"),
            )
        }
    }

    /**
     * Adds (or re-uses) [ssid] and selects it. An empty [password] means an open
     * network.
     */
    @Suppress("DEPRECATION")
    private fun connect(
        wifi: WifiManager,
        ssid: String,
        password: String,
        result: JSONObject,
    ) {
        result.put("action", "connect")

        if (ssid.isEmpty()) {
            result.put("connected", false).put("reason", "no ssid supplied")
            return
        }

        // Re-use a configured network with the same SSID rather than adding a second.
        val existing = wifi.configuredNetworks.orEmpty().firstOrNull { it.SSID == quote(ssid) }

        val config =
            existing ?: WifiConfiguration().apply {
                SSID = quote(ssid)
                if (password.isEmpty()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = quote(password)
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
            }

        val netId = if (existing != null) existing.networkId else wifi.addNetwork(config)
        if (netId < 0) {
            result.put("connected", false).put("reason", "the network could not be added")
            return
        }

        // One network at a time: disable the rest, enable this, reconnect.
        wifi.configuredNetworks.orEmpty().forEach { other ->
            if (other.networkId != netId) wifi.disableNetwork(other.networkId)
        }

        wifi.enableNetwork(netId, true)
        wifi.reconnect()

        result
            .put("connected", true)
            .put("networkId", netId)
            .put("open", password.isEmpty())
    }

    /** The visible networks from the last scan. */
    @Suppress("DEPRECATION")
    private fun scanResults(wifi: WifiManager): JSONArray {
        val array = JSONArray()

        for (scan in wifi.scanResults.orEmpty()) {
            array.put(
                JSONObject()
                    .put("ssid", scan.SSID.orEmpty())
                    .put("bssid", scan.BSSID.orEmpty())
                    .put("level", scan.level)
                    .put("frequency", scan.frequency)
                    .put("capabilities", scan.capabilities.orEmpty()),
            )
        }

        return array
    }

    /** The configured networks. */
    @Suppress("DEPRECATION")
    private fun configured(wifi: WifiManager): JSONArray {
        val array = JSONArray()

        for (config in wifi.configuredNetworks.orEmpty()) {
            array.put(
                JSONObject()
                    .put("networkId", config.networkId)
                    .put("ssid", config.SSID.orEmpty())
                    .put("priority", config.priority),
            )
        }

        return array
    }

    /** Wraps an SSID or password in the quotes the WifiConfiguration API requires. */
    private fun quote(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""

    /**
     * The tethering restrictions in force, named.
     *
     * `no_config_tethering` blocks all tethering (and the Settings UI); `no_wifi_tethering`
     * blocks Wi-Fi tethering only and is superseded by the former. Both are read so the
     * report is specific rather than a bare boolean, and `canUnblock` says whether this
     * app could clear them (a device owner could; a plain app cannot).
     */
    private fun tetheringRestrictions(context: Context): JSONObject {
        val users = context.getSystemService(Context.USER_SERVICE) as? UserManager

        val allBlocked =
            users?.userRestrictions?.getBoolean(UserManager.DISALLOW_CONFIG_TETHERING, false) ==
                true
        val wifiBlocked =
            users?.userRestrictions?.getBoolean(UserManager.DISALLOW_WIFI_TETHERING, false) ==
                true

        return JSONObject()
            .put("allTetheringBlocked", allBlocked)
            .put("wifiTetheringBlocked", wifiBlocked)
            // The restriction to name when explaining a greyed-out hotspot toggle.
            .put(
                "blockedBy",
                when {
                    allBlocked -> UserManager.DISALLOW_CONFIG_TETHERING
                    wifiBlocked -> UserManager.DISALLOW_WIFI_TETHERING
                    else -> ""
                },
            ).put("canUnblock", DeviceSettingsWriter.isDeviceOwner(context))
    }
}
