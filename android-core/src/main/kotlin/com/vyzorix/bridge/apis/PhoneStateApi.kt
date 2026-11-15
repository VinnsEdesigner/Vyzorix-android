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
 * Telephony state: the network user and the phone type.
 *
 * Gated on `READ_BASIC_PHONE_STATE` (API 33+, protection `normal`), not the
 * dangerous `READ_PHONE_STATE`. The fields needing the stronger grant —
 * software version, network type, roaming — are in `phone_state_detailed`.
 *
 * No subscriber identifiers: IMEI, IMSI and the phone number are not readable by a
 * normal app on modern Android, and where they are they are the most sensitive
 * values on the device. They are not returned even when a permission would allow it.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_DANGEROUS,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object PhoneStateApi : ApiHandler {

    const val NAME = "phone_state"

    /**
     * The phone's type, user and SIM facts.
     *
     * Gated on `READ_BASIC_PHONE_STATE` (API 33+, protection `normal`, no dialog) so
     * the API works without a prompt. Nothing here needs the dangerous grant.
     */
    @SuppressLint("MissingPermission")
    override fun handle(call: ApiCall): ApiOutcome {
        val telephony =
            call.context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no telephony service")

        // `phoneType` is a constant, not an enum: the number is reported alongside a
        // name so a caller can use either without depending on undocumented values.
        val type = telephony.phoneType

        return ApiOutcome.ok(
            JSONObject()
                .put("phoneType", type)
                .put("phoneTypeName", phoneTypeName(type))
                .put("networkOperatorName", telephony.networkOperatorName.orEmpty())
                .put("networkOperator", telephony.networkOperator.orEmpty())
                .put("simOperatorName", telephony.simOperatorName.orEmpty())
                .put("simCountryIso", telephony.simCountryIso.orEmpty())
                .put("networkCountryIso", telephony.networkCountryIso.orEmpty())
                .put("simState", telephony.simState),
        )
    }

    /** A readable name for the phone type, because the constant means nothing to a reader. */
    private fun phoneTypeName(type: Int): String =
        when (type) {
            TelephonyManager.PHONE_TYPE_GSM -> "gsm"
            TelephonyManager.PHONE_TYPE_CDMA -> "cdma"
            TelephonyManager.PHONE_TYPE_SIP -> "sip"
            else -> "none"
        }
}
