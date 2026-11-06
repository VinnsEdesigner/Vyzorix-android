package com.vyzorix.bridge.apis

import android.os.Environment
import android.os.StatFs
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Shared-storage capacity, and whether it is emulated or real. An operator sizing
 * an export needs this, and the emulated flag explains why the numbers move with
 * `/data`. No runtime permission on API 26 for the app's own view.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object ExternalStorageApi : ApiHandler {

    const val NAME = "external_storage"

    override fun handle(call: ApiCall): ApiOutcome {
        val root = Environment.getExternalStorageDirectory()
        if (root == null || !root.exists()) {
            return ApiOutcome.ok(
                JSONObject().put("available", false).put("path", ""),
            )
        }

        val stat = StatFs(root.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong

        return ApiOutcome.ok(
            JSONObject()
                .put("available", true)
                .put("path", root.absolutePath)
                .put("totalBytes", total)
                .put("freeBytes", free)
                .put("usedBytes", total - free)
                .put("emulated", Environment.isExternalStorageEmulated()),
        )
    }
}
