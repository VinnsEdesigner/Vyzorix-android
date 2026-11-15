package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.capability.usb.UsbBroker
import org.json.JSONArray
import org.json.JSONObject

/**
 * USB state and what is attached. The attachment list is what a user checks
 * before asking the device to drive a flasher. No runtime permission.
 *
 * `hw:usb` and `data:hardware` because this reports physical hardware, and
 * `protection:signature` to match the server catalog's USB capability class — the
 * device never evaluates the tag, but the two classifications must agree.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_HARDWARE,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_SIGNATURE,
        ApiTags.VISIBILITY_PRIVATE,
        ApiTags.HW_USB,
    ],
)
class UsbStateApi(
    private val broker: UsbBroker,
) : ApiHandler {

    override fun handle(call: ApiCall): ApiOutcome {
        val devices = JSONArray()
        for (device in broker.devices()) {
            devices.put(
                JSONObject()
                    .put("name", device.name)
                    .put("vendorId", device.vendorId)
                    .put("productId", device.productId)
                    .put("deviceClass", device.deviceClass)
                    .put("product", device.productName)
                    .put("manufacturer", device.manufacturerName)
                    .put("serial", device.serial)
                    .put("interfaceCount", device.interfaceCount)
                    .put("hasPermission", device.hasPermission)
                    // True when this bridge holds the device open, which is what a
                    // follow-up usb_attach would reuse rather than re-open.
                    .put("attached", broker.isOpen(device.name)),
            )
        }

        return ApiOutcome.ok(
            JSONObject()
                .put("devices", devices)
                .put("count", devices.length())
                .put("openCount", broker.openCount()),
        )
    }

    companion object {
        const val NAME = "usb_state"
    }
}
