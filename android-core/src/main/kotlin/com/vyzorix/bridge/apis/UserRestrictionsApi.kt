package com.vyzorix.bridge.apis

import android.content.Context
import android.os.UserManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.service.DeviceSettingsWriter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which device features a policy has **restricted**, from the whole AOSP user-restriction
 * vocabulary.
 *
 * ## The question this answers
 *
 * "Something on this phone is blocked and I do not know what." A managed or OEM-hardened
 * device carries a set of `UserManager` restrictions (`no_usb_file_transfer`,
 * `no_config_tethering`, `no_factory_reset`, ...), and each one switches off a specific
 * feature. They are not discoverable from the UI, and reading them one key at a time is
 * guesswork, so this returns the *whole* vocabulary with the state of each — every feature
 * the platform can restrict, marked restricted or not.
 *
 * ## What is in the catalog, and why it is fixed
 *
 * [CATALOG] is the complete `UserManager.DISALLOW_*` list from AOSP, keyed by its string
 * value, each with a plain-language meaning. It is a fixed list on purpose: the value of
 * this API is that a caller learns about a restriction it did not know to ask for, so the
 * keys are enumerated here rather than discovered from the active set. A restriction the
 * platform knows and this catalog does not (an OEM addition) is not lost — it is reported
 * under `unknown`, so nothing active is ever hidden.
 *
 * ## What it deliberately does not report
 *
 * **Who** set a restriction. The active set is readable without any permission, but the
 * setter is not: it takes `dumpsys device_policy`, which a normal app cannot read. So the
 * answer is "this feature is restricted", never "this app restricted it" — claiming the
 * latter would be a guess.
 *
 * ## Read-only
 *
 * No clearing here. Lifting a restriction is `clearUserRestriction`, which only a device
 * owner (or a `MANAGE_DEVICE_POLICY_*` holder) may call, and doing it from a query API
 * would be a destructive side effect of a read. `canClear` reports whether *this* app
 * could, so a caller knows whether to expect that to be possible.
 *
 * No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object UserRestrictionsApi : ApiHandler {

    const val NAME = "user_restrictions"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val users = context.getSystemService(Context.USER_SERVICE) as? UserManager

        // The active set, key -> true. Readable with no permission for the current user.
        val active =
            users?.userRestrictions?.let { bundle ->
                bundle.keySet().filter { bundle.getBoolean(it, false) }.toSet()
            } ?: emptySet()

        val known = CATALOG.map { it.key }.toSet()
        // An active key the catalog does not name (an OEM addition). Reported so a
        // restriction is never silently dropped just because we did not list it.
        val unknown = active.filterNot { it in known }.sorted()

        // Restricted features first, so "what is blocked" is the head of the list; then
        // alphabetical, so the order is stable.
        val ordered =
            CATALOG.sortedWith(
                compareByDescending<Restriction> { it.key in active }
                    .thenBy { it.key },
            )

        val array = JSONArray()
        for (restriction in ordered) {
            array.put(
                JSONObject()
                    .put("key", restriction.key)
                    .put("meaning", restriction.meaning)
                    .put("restricted", restriction.key in active),
            )
        }

        val activeJson = JSONObject()
        for (key in active.sorted()) {
            activeJson.put(key, true)
        }

        return ApiOutcome.ok(
            JSONObject()
                .put("restrictions", array)
                .put("catalogCount", CATALOG.size)
                // The count that matters: how many features are switched off.
                .put("restrictedCount", active.size)
                .put("anyRestricted", active.isNotEmpty())
                // The restricted keys alone, for a caller that only wants the blocked set.
                .put("active", activeJson)
                // Active restrictions the AOSP catalog does not name (OEM additions).
                .put("unknown", JSONArray(unknown))
                .put("isDeviceOwner", DeviceSettingsWriter.isDeviceOwner(context))
                // Whether *this* app could lift a restriction at all: only a device owner
                // (or a MANAGE_DEVICE_POLICY_* holder) may call clearUserRestriction.
                .put("canClear", DeviceSettingsWriter.isDeviceOwner(context)),
        )
    }

    /** One restriction: its AOSP string value and what turning it on blocks. */
    private class Restriction(
        val key: String,
        val meaning: String,
    )

    /**
     * The complete AOSP `UserManager.DISALLOW_*` vocabulary, key -> meaning.
     *
     * Sourced from `UserManager` in `frameworks/base`; the meanings are the doc strings in
     * plain language. A fixed list, so a caller learns about a restriction it would not
     * have thought to ask for.
     */
    private val CATALOG: List<Restriction> =
        listOf(
            Restriction("no_add_clone_profile", "Add clone profiles"),
            Restriction("no_add_managed_profile", "Add managed (work) profiles"),
            Restriction("no_add_private_profile", "Add private profiles"),
            Restriction("no_add_user", "Add users"),
            Restriction("no_add_wifi_config", "Add Wi-Fi networks"),
            Restriction("no_adjust_volume", "Adjust volume"),
            Restriction("no_airplane_mode", "Toggle airplane mode"),
            Restriction("no_ambient_display", "Ambient display"),
            Restriction("no_control_apps", "Force-stop or clear app data"),
            Restriction("no_assist_content", "Assist content"),
            Restriction("no_autofill", "Autofill"),
            Restriction("disallow_biometric", "Biometric unlock"),
            Restriction("no_bluetooth", "Bluetooth"),
            Restriction("no_bluetooth_sharing", "Bluetooth sharing"),
            Restriction("no_camera", "Camera"),
            Restriction("no_change_wifi_state", "Change Wi-Fi state"),
            Restriction("no_config_bluetooth", "Configure Bluetooth"),
            Restriction("no_config_brightness", "Configure brightness"),
            Restriction("no_config_cell_broadcasts", "Cell broadcast settings"),
            Restriction("no_config_credentials", "Configure credentials (VPN, certificates)"),
            Restriction("no_config_date_time", "Configure date and time"),
            Restriction("disallow_config_default_apps", "Configure default apps"),
            Restriction("no_config_locale", "Configure locale"),
            Restriction("no_config_location", "Configure location settings"),
            Restriction("no_config_mobile_networks", "Configure mobile networks"),
            Restriction("no_config_screen_timeout", "Configure screen timeout"),
            Restriction("no_config_tethering", "Configure tethering"),
            Restriction("no_config_vpn", "Configure VPN"),
            Restriction("no_config_wifi", "Configure Wi-Fi"),
            Restriction("no_content_capture", "Content capture"),
            Restriction("no_content_suggestions", "Content suggestions"),
            Restriction("no_create_windows", "Create windows"),
            Restriction("no_cross_profile_copy_paste", "Cross-profile copy and paste"),
            Restriction("no_data_roaming", "Data roaming"),
            Restriction("no_debugging_features", "Developer options and USB debugging"),
            Restriction("no_factory_reset", "Factory reset"),
            Restriction("no_fun", "Fun and games"),
            Restriction("no_grant_admin", "Grant device admin"),
            Restriction("no_install_apps", "Install apps"),
            Restriction("no_install_unknown_sources", "Install apps from unknown sources"),
            Restriction("no_modify_accounts", "Add or modify accounts"),
            Restriction("no_physical_media", "Mount physical media"),
            Restriction("no_network_reset", "Network reset"),
            Restriction("no_oem_unlock", "OEM bootloader unlock"),
            Restriction("no_outgoing_beam", "Outgoing NFC beam"),
            Restriction("no_outgoing_calls", "Outgoing calls"),
            Restriction("no_printing", "Printing"),
            Restriction("no_record_audio", "Record audio"),
            Restriction("no_remove_managed_profile", "Remove a managed profile"),
            Restriction("no_remove_user", "Remove users"),
            Restriction("no_run_in_background", "Run in background"),
            Restriction("no_safe_boot", "Safe boot"),
            Restriction("no_set_user_icon", "Set the user icon"),
            Restriction("no_set_wallpaper", "Set the wallpaper"),
            Restriction("no_sharing_into_profile", "Share into a managed profile"),
            Restriction("no_share_location", "Share location"),
            Restriction("no_sms", "Send and receive SMS"),
            Restriction("no_system_error_dialogs", "System error dialogs"),
            Restriction("no_thread_network", "Thread network"),
            Restriction("no_ultra_wideband_radio", "Ultra-wideband (UWB) radio"),
            Restriction("no_unified_password", "Unified lock password (profile)"),
            Restriction("no_uninstall_apps", "Uninstall apps"),
            Restriction("disallow_unmute_device", "Unmute the device"),
            Restriction("no_unmute_microphone", "Unmute the microphone"),
            Restriction("no_usb_file_transfer", "USB file transfer"),
            Restriction("no_user_switch", "Switch users"),
            Restriction("no_wallpaper", "Change the wallpaper (deprecated alias)"),
            Restriction("no_wifi_direct", "Wi-Fi Direct"),
            Restriction("no_wifi_tethering", "Wi-Fi tethering (hotspot)"),
        )
}
