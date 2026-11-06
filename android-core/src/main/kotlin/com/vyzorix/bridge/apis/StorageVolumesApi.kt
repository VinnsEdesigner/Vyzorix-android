package com.vyzorix.bridge.apis

import android.content.Context
import android.os.storage.StorageManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every storage volume the system knows about, with its state. Where
 * `external_storage` answers about one path, this answers about the device. No
 * runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object StorageVolumesApi : ApiHandler {

    const val NAME = "storage_volumes"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no storage service",
                )

        val array = JSONArray()
        for (volume in manager.storageVolumes) {
            // `StorageVolume.getDirectory()` is API 30 and minSdk is 26, and there is
            // no pre-30 equivalent that returns the mount path — `StorageVolume` did
            // not expose one. Android lint caught the unguarded call as a NewApi
            // error, which is why that check is an error rather than a warning: on a
            // 26 device it is a NoSuchMethodError.
            //
            // Below 30 the path is therefore reported as absent rather than guessed
            // from the uuid, which would be a plausible-looking string that is wrong.
            val path =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    volume.directory?.absolutePath.orEmpty()
                } else {
                    ""
                }

            array.put(
                JSONObject()
                    .put("uuid", volume.uuid.orEmpty())
                    .put("description", volume.getDescription(call.context))
                    .put("state", volume.state)
                    .put("emulated", volume.isEmulated)
                    .put("removable", volume.isRemovable)
                    .put("primary", volume.isPrimary)
                    .put("path", path),
            )
        }

        return ApiOutcome.ok(JSONObject().put("volumes", array).put("count", array.length()))
    }
}
