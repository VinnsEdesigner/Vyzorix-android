package com.vyzorix.bridge.apis

import android.app.NotificationManager
import android.content.Context
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Whether Do Not Disturb is on. Read from NotificationManager, which reports the
 * interruption filter. An user paging a device wants to know it will be silent.
 * No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object DoNotDisturbApi : ApiHandler {

    const val NAME = "do_not_disturb"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no notification service",
                )

        val filter = manager.currentInterruptionFilter

        return ApiOutcome.ok(
            JSONObject()
                .put("interruptionFilter", filter)
                .put("dnd", filter != NotificationManager.INTERRUPTION_FILTER_ALL)
                .put("policyAccess", manager.isNotificationPolicyAccessGranted),
        )
    }
}
