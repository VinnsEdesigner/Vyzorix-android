package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import java.io.File
import org.json.JSONObject

/**
 * The app's own storage: the private files directory, the cache, and how much of
 * each is in use. This is where the rootfs and the apps live, so it is the number
 * that matters for an install. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object AppStorageApi : ApiHandler {

    const val NAME = "app_storage"

    override fun handle(call: ApiCall): ApiOutcome {
        val files = call.context.filesDir
        val cache = call.context.cacheDir

        return ApiOutcome.ok(
            JSONObject()
                .put("filesDir", files.absolutePath)
                .put("filesBytes", directorySize(files))
                .put("cacheDir", cache.absolutePath)
                .put("cacheBytes", directorySize(cache))
                .put(
                    "dataDir",
                    call.context.applicationInfo
                        ?.dataDir
                        .orEmpty(),
                ),
        )
    }

    /** Total bytes of every regular file under `dir`, bounded so a huge tree is capped. */
    private fun directorySize(dir: File?): Long {
        if (dir == null || !dir.isDirectory) return 0L

        var total = 0L
        var visited = 0
        val queue = ArrayDeque(listOf(dir))

        while (queue.isNotEmpty() && visited < MAX_ENTRIES) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue

            for (child in children) {
                visited++
                if (visited >= MAX_ENTRIES) break
                if (child.isDirectory) queue.addLast(child) else total += child.length()
            }
        }

        return total
    }

    /** A tree larger than this is reported as a partial sum rather than walked forever. */
    private const val MAX_ENTRIES = 20_000
}
