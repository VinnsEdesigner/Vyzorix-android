package com.vyzorix.bridge.apis

import android.os.Build
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * The build identity, as the platform reports it. Reported whole rather than
 * parsed: the fingerprint's format is not something Google guarantees, so splitting
 * it here would invite a caller to depend on it. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object BuildFingerprintApi : ApiHandler {

    const val NAME = "build_fingerprint"

    override fun handle(call: ApiCall): ApiOutcome =
        ApiOutcome.ok(
            JSONObject()
                .put("fingerprint", Build.FINGERPRINT)
                .put("id", Build.ID)
                .put("display", Build.DISPLAY)
                .put("product", Build.PRODUCT)
                .put("device", Build.DEVICE)
                .put("brand", Build.BRAND)
                .put("tags", Build.TAGS)
                .put("type", Build.TYPE)
                .put("timeMs", Build.TIME),
        )
}
