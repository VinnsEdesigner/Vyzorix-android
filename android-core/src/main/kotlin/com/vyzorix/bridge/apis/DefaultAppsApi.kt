package com.vyzorix.bridge.apis

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Which app handles the common intents. A flasher that wants to open a URL in a
 * browser needs to know whether one is installed at all. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object DefaultAppsApi : ApiHandler {

    const val NAME = "default_apps"

    override fun handle(call: ApiCall): ApiOutcome {
        val result = JSONObject()

        for ((role, intent) in ROLES) {
            val resolved =
                call.context.packageManager.resolveActivity(
                    intent,
                    PackageManager.MATCH_DEFAULT_ONLY,
                )

            result.put(
                role,
                JSONObject()
                    .put("resolved", resolved != null)
                    .put("packageName", resolved?.activityInfo?.packageName.orEmpty()),
            )
        }

        return ApiOutcome.ok(result)
    }

    /** The roles an operator cares about, and the intent that resolves each. */
    private val ROLES =
        linkedMapOf(
            "browser" to Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE),
            "dialer" to Intent(Intent.ACTION_DIAL),
            "email" to Intent(Intent.ACTION_SENDTO).setData(Uri.parse("mailto:")),
            "camera" to Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            "share" to Intent(Intent.ACTION_SEND).setType("text/plain"),
        )
}
