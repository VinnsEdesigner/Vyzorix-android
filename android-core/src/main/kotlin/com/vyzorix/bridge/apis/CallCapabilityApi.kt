package com.vyzorix.bridge.apis

import android.content.Context
import android.content.pm.PackageManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Whether the device can place calls, and whether a dialer exists to hand one to.
 *
 * Read-only, and deliberately so: `CALL_PHONE` is a dangerous permission and dialing
 * is a write. The capability is what a user needs before offering a "call this
 * number" action, and it needs no permission to report.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object CallCapabilityApi : ApiHandler {

    const val NAME = "call_capability"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val packageManager = context.packageManager

        // `FEATURE_TELEPHONY` is absent on a Wi-Fi-only tablet, which is the case
        // this exists to distinguish from "the dialer is missing".
        val telephony = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val telephonyCalling =
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)

        val dialer =
            context.getSystemService(Context.TELECOM_SERVICE)?.let { service ->
                val manager = service as? android.telecom.TelecomManager
                manager?.defaultDialerPackage
            }

        // The permission is reported rather than requested: the caller decides
        // whether to ask, and the router's own gate is what enforces it.
        val callPermission = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE)

        return ApiOutcome.ok(
            JSONObject()
                .put("telephony", telephony)
                .put("telephonyCalling", telephonyCalling)
                .put("defaultDialer", dialer.orEmpty())
                .put("hasDialer", !dialer.isNullOrEmpty())
                .put("callPhoneGranted", callPermission == PackageManager.PERMISSION_GRANTED),
        )
    }
}
