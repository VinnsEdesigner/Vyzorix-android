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
 * Internal storage capacity. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object StorageApi : ApiHandler {

    const val NAME = "storage"

    override fun handle(call: ApiCall): ApiOutcome {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)

        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong

        return ApiOutcome.ok(
            JSONObject()
                .put("totalBytes", total)
                .put("freeBytes", free)
                .put("usedBytes", total - free),
        )
    }
}
