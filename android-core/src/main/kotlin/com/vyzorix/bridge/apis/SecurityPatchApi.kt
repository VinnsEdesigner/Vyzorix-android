package com.vyzorix.bridge.apis

import android.content.pm.ApplicationInfo
import android.os.Build
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * The security patch level, and whether the build is debuggable. A device on an old
 * patch is a fact a user should see before trusting it. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object SecurityPatchApi : ApiHandler {

    const val NAME = "security_patch"

    override fun handle(call: ApiCall): ApiOutcome {
        val flags = call.context.applicationInfo?.flags ?: 0
        val debuggable = flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        return ApiOutcome.ok(
            JSONObject()
                .put("securityPatch", Build.VERSION.SECURITY_PATCH)
                .put("debuggable", debuggable)
                .put("userBuild", "user" == Build.TYPE)
                .put("bootloader", Build.BOOTLOADER),
        )
    }
}
