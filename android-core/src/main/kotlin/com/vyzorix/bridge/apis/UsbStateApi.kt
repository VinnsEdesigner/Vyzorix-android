package com.vyzorix.bridge.apis

import android.content.Context
import android.hardware.usb.UsbManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * USB state and what is attached. The attachment list is what an operator checks
 * before asking the device to drive a flasher. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object UsbStateApi : ApiHandler {

    const val NAME = "usb_state"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no usb service",
                )

        val devices = JSONArray()
        for ((name, device) in manager.deviceList) {
            devices.put(
                JSONObject()
                    .put("name", name)
                    .put("vendorId", device.vendorId)
                    .put("productId", device.productId)
                    .put("deviceClass", device.deviceClass)
                    .put("product", device.productName.orEmpty())
                    .put("hasPermission", manager.hasPermission(device)),
            )
        }

        return ApiOutcome.ok(
            JSONObject().put("devices", devices).put("count", devices.length()),
        )
    }
}
