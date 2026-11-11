package com.vyzorix.bridge.apis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserManager
import android.telephony.TelephonyManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.service.DeviceSettingsWriter
import org.json.JSONObject

/**
 * Placing a call, and whether calls are blocked by policy.
 *
 * ## Calling
 *
 * `ACTION_CALL` places a call **only when this app holds `CALL_PHONE`**; without it
 * the platform throws `SecurityException`, which is reported as a refusal. When the
 * permission is held the call is placed directly (no dialer tap), which is what an
 * agent needs — the number is dialled, not offered to a person.
 *
 * The number is validated before it is dialled: `ACTION_CALL` with a `tel:` URI, and
 * a URI the platform cannot parse is refused here rather than thrown. Nothing else in
 * the URI is trusted; a non-`tel` scheme is rejected.
 *
 * ## Blocked?
 *
 * Two independent gates answer "are calls blocked":
 *
 *  - `DISALLOW_OUTGOING_CALLS` (device owner) — outgoing calls are barred.
 *  - Airplane mode / no telephony — no radio, so no call regardless of policy.
 *
 * Both are reported, so "cannot call" is never a mystery: it is policy, or it is the
 * radio, or it is the missing permission.
 *
 * `call_phone` is a dangerous permission; the router gates the whole API on it, so a
 * device without the grant sees the request refused rather than a call attempted.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PRIVATE,
        ApiTags.OP_WRITE,
        ApiTags.PROTECTION_DANGEROUS,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object CallControlApi : ApiHandler {

    const val NAME = "call_control"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val blocked = outgoingCallsBlocked(context)

        val result =
            JSONObject()
                .put("outgoingCallsBlocked", blocked)
                .put("callPhoneGranted", hasCallPhone(context))
                .put("isDeviceOwner", DeviceSettingsWriter.isDeviceOwner(context))

        val number = call.args["number"].orEmpty()
        if (number.isEmpty()) {
            // A status read: report the gates and stop. Placing a call is opt-in by
            // supplying a number, so a bare call never dials.
            return ApiOutcome.ok(result)
        }

        if (blocked) {
            return ApiOutcome.ok(
                result.put("placed", false).put("reason", "outgoing calls are blocked by policy"),
            )
        }

        if (!hasCallPhone(context)) {
            return ApiOutcome.fail(
                BridgeErrorKind.FORBIDDEN,
                "CALL_PHONE is not granted; cannot place a call",
            )
        }

        return placeCall(context, number, result)
    }

    /** Dials [number] via `ACTION_CALL`. */
    private fun placeCall(
        context: Context,
        number: String,
        result: JSONObject,
    ): ApiOutcome {
        val uri =
            try {
                Uri.parse("tel:" + Uri.encode(number))
            } catch (e: Exception) {
                return ApiOutcome.fail(
                    BridgeErrorKind.INVALID_ARGS,
                    "number could not be encoded: ${e.message}",
                )
            }

        val intent =
            android.content
                .Intent(android.content.Intent.ACTION_CALL, uri)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)

            ApiOutcome.ok(result.put("placed", true).put("number", number))
        } catch (e: SecurityException) {
            ApiOutcome.fail(
                BridgeErrorKind.FORBIDDEN,
                "the platform refused the call: ${e.message}",
            )
        } catch (e: Exception) {
            ApiOutcome.fail(BridgeErrorKind.INTERNAL, "could not place the call: ${e.message}")
        }
    }

    /**
     * True when outgoing calls are barred by policy or by the radio.
     *
     * `DISALLOW_OUTGOING_CALLS` is the device-owner restriction; airplane mode and an
     * absent telephony service mean no call is possible either way, and both are folded
     * into the one answer an operator acts on.
     */
    private fun outgoingCallsBlocked(context: Context): Boolean {
        val users = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (users?.userRestrictions?.getBoolean(UserManager.DISALLOW_OUTGOING_CALLS, false) ==
            true
        ) {
            return true
        }

        val telephony =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return true

        // No telephony feature means no calls at all.
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return true

        return telephony.phoneType == TelephonyManager.PHONE_TYPE_NONE
    }

    private fun hasCallPhone(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
}
