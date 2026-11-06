package com.vyzorix.bridge.apis

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
 * Telephony state: the network operator and the phone type.
 *
 * `READ_PHONE_STATE` is a **dangerous** permission, so this is refused until it is
 * granted — and on Android 12+ the permission is split, so the fields that would
 * need `READ_PHONE_NUMBERS` are deliberately absent. What is reported is what the
 * base permission covers.
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

    override fun handle(call: ApiCall): ApiOutcome {
        val telephony =
            call.context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no telephony service")

        // `phoneType` is a constant, not an enum: reporting the number would make a
        // caller depend on values the platform does not guarantee, so it is named
        // here and the number is reported alongside for a caller that wants it.
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
                .put("isNetworkRoaming", telephony.isNetworkRoaming)
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
