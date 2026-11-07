package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.capability.usb.UsbBroker
import org.json.JSONObject

/**
 * Closes a USB device opened by `usb_attach` and drops its file descriptor.
 *
 * Detaching is the revocation: the descriptor the container holds is a duplicate,
 * so closing the broker's copy does not by itself unmap the container's — but the
 * broker stops handing it out, and the next container restart finds nothing. The
 * authoritative revocation for a live session is the token expiry and the process
 * teardown; this API stops the capability being reissued and releases the device
 * so another app can claim it.
 *
 * Idempotent: detaching a device that is not open is not an error, because an
 * operator's detach races the device being unplugged.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_HARDWARE,
        ApiTags.OP_WRITE,
        ApiTags.PROTECTION_SIGNATURE,
        ApiTags.VISIBILITY_PRIVATE,
        ApiTags.HW_USB,
    ],
)
class UsbDetachApi(
    private val broker: UsbBroker,
) : ApiHandler {

    override fun handle(call: ApiCall): ApiOutcome {
        val name = call.args["name"]?.trim().orEmpty()
        if (name.isEmpty()) {
            return ApiOutcome.fail(BridgeErrorKind.INVALID_ARGS, "name is required")
        }

        broker.detach(name)

        return ApiOutcome.ok(
            JSONObject()
                .put("name", name)
                .put("attached", false),
        )
    }

    companion object {
        const val NAME = "usb_detach"
    }
}
