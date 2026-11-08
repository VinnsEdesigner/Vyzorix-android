package com.vyzorix.bridge.apis

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.TelephonyManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Telephony fields gated on `READ_PHONE_STATE`: software version, network type and
 * roaming. Split from `phone_state` because these need the dangerous permission
 * while the rest does not, and gating one API on the stronger grant would make the
 * weaker facts unavailable to a user who declines the dialog.
 *
 * No subscriber identifiers. IMEI, IMSI and the phone number need
 * `READ_PRIVILEGED_PHONE_STATE`, which a normal app cannot hold, and they are the
 * most sensitive values on the device. They are not read even where a grant exists.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PRIVATE,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_DANGEROUS,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object PhoneStateDetailedApi : ApiHandler {

    const val NAME = "phone_state_detailed"

    @SuppressLint("MissingPermission")
    override fun handle(call: ApiCall): ApiOutcome {
        val telephony =
            call.context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no telephony service")

        return ApiOutcome.ok(
            JSONObject()
                .put("softwareVersion", telephony.deviceSoftwareVersion.orEmpty())
                .put("dataNetworkType", networkTypeName(telephony.dataNetworkType))
                .put("voiceNetworkType", networkTypeName(telephony.voiceNetworkType))
                .put("isNetworkRoaming", telephony.isNetworkRoaming),
        )
    }

    /** Names for the common `NETWORK_TYPE_*` constants; the rest report the number. */
    private fun networkTypeName(type: Int): String =
        when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "gprs"
            TelephonyManager.NETWORK_TYPE_EDGE -> "edge"
            TelephonyManager.NETWORK_TYPE_UMTS -> "umts"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "hsdpa"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "hsupa"
            TelephonyManager.NETWORK_TYPE_HSPA -> "hspa"
            TelephonyManager.NETWORK_TYPE_CDMA -> "cdma"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "evdo0"
            TelephonyManager.NETWORK_TYPE_EVDO_A -> "evdoa"
            TelephonyManager.NETWORK_TYPE_LTE -> "lte"
            TelephonyManager.NETWORK_TYPE_NR -> "nr"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"
            else -> type.toString()
        }
}
