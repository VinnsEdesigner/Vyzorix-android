package com.vyzorix.bridge.apis

import android.content.Context
import android.hardware.input.InputManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * The input devices the system knows about. An user driving a screen wants to
 * know whether a touchscreen, a stylus or a keyboard is present. No runtime
 * permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object InputDevicesApi : ApiHandler {

    const val NAME = "input_devices"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.INPUT_SERVICE) as? InputManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no input service",
                )

        val array = JSONArray()
        for (id in manager.inputDeviceIds) {
            val device = manager.getInputDevice(id) ?: continue

            array.put(
                JSONObject()
                    .put("id", id)
                    .put("name", device.name)
                    .put("descriptor", device.descriptor)
                    .put("sources", device.sources)
                    .put("keyboardType", device.keyboardType),
            )
        }

        return ApiOutcome.ok(JSONObject().put("devices", array).put("count", array.length()))
    }
}
