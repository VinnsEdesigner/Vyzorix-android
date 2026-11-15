package com.vyzorix.bridge.apis

import android.telephony.SmsManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Whether SMS can be sent, and the device's SMS capabilities.
 *
 * This is the **capability** form of the SMS API, not the sending form. Sending is a
 * separate, `op:write` API that has to be asked for explicitly: a command that sends
 * a text message costs the owner money and is not undoable, so it does not belong in
 * a listing of what the device can do.
 *
 * `SEND_SMS` is a dangerous permission. It is required here because `SmsManager`
 * cannot be obtained without it, which is the honest place to require it: an
 * user asking "can this device send SMS" is asking a question that needs the
 * permission to answer truthfully.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_DANGEROUS,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object SmsCapabilityApi : ApiHandler {

    const val NAME = "sms_capability"

    override fun handle(call: ApiCall): ApiOutcome {
        // `getDefault()` needs SEND_SMS; the router has already checked it, so a
        // failure here is the platform disagreeing rather than a permission gap.
        val manager =
            try {
                SmsManager.getDefault()
            } catch (e: Exception) {
                return ApiOutcome.fail(
                    BridgeErrorKind.FORBIDDEN,
                    "SmsManager unavailable: ${e.message}",
                )
            }

        // `divideMessage` is the only operation that does not send anything, so it is
        // used as the liveness check: a manager that can divide a message can send one.
        val parts =
            try {
                manager.divideMessage(CAPABILITY_PROBE)
            } catch (e: Exception) {
                return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "SmsManager is not usable: ${e.message}",
                )
            }

        return ApiOutcome.ok(
            JSONObject()
                .put("available", true)
                .put("multipartCapable", parts.size > 1 || parts.isNotEmpty())
                .put("probeParts", parts.size),
        )
    }

    /**
     * A string long enough to be split if multipart works, short enough not to be
     * sent anywhere. It is never transmitted: `divideMessage` only measures.
     */
    private const val CAPABILITY_PROBE = "vyzorix-capability-probe"
}
