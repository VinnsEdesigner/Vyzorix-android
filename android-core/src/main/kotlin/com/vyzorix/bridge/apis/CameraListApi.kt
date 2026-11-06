package com.vyzorix.bridge.apis

import android.content.Context
import android.hardware.camera2.CameraManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * The camera ids the device exposes. Enumerating cameras needs no permission — only
 * opening one does — so this is a plain listing that reports ids and nothing else.
 * No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object CameraListApi : ApiHandler {

    const val NAME = "camera_list"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no camera service",
                )

        var cameraError = ""
        val ids =
            try {
                manager.cameraIdList
            } catch (e: Exception) {
                // A device with no camera reports exactly this way, which is a fact
                // rather than a failure. The message is returned so the caller can
                // tell "no camera" from "camera service broken".
                cameraError = e.message.orEmpty()
                emptyArray()
            }

        return ApiOutcome.ok(
            JSONObject()
                .put("ids", JSONArray(ids))
                .put("count", ids.size)
                .put("detail", cameraError),
        )
    }
}
