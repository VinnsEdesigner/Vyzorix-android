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
 * Whether notifications are enabled for this app, and the system's notification
 * policy access state. A device whose notifications are off will not wake for a
 * command, which is worth knowing. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object NotificationPolicyApi : ApiHandler {

    const val NAME = "notification_policy"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no notification service",
                )

        return ApiOutcome.ok(
            JSONObject()
                .put("notificationsEnabled", manager.areNotificationsEnabled())
                .put("policyAccess", manager.isNotificationPolicyAccessGranted),
        )
    }
}
