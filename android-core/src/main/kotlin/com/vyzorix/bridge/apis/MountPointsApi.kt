package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import java.io.File
import org.json.JSONObject

/**
 * The filesystem mounts the app can see. Read from `/proc/mounts`, which is
 * world-readable. An operator uses it to tell whether `/sdcard` is real storage
 * or FUSE, and whether the container's binds landed. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object MountPointsApi : ApiHandler {

    const val NAME = "mount_points"

    /** `/proc/mounts` is readable by every uid, unlike `/proc/self/mountinfo`. */
    private const val MOUNTS = "/proc/mounts"

    /** Six whitespace-separated fields per line; only the first three are useful. */
    private const val FIELDS = 6

    override fun handle(call: ApiCall): ApiOutcome {
        val file = File(MOUNTS)
        if (!file.canRead()) {
            return ApiOutcome.fail(
                BridgeErrorKind.INTERNAL,
                "$MOUNTS is not readable on this device",
            )
        }

        val array = org.json.JSONArray()
        file.readLines().forEach { line ->
            val parts = line.split(' ')
            if (parts.size < FIELDS) return@forEach

            array.put(
                JSONObject()
                    .put("device", parts[0])
                    .put("mountPoint", parts[1])
                    .put("type", parts[2])
                    .put("options", parts[3]),
            )
        }

        return ApiOutcome.ok(JSONObject().put("mounts", array).put("count", array.length()))
    }
}
