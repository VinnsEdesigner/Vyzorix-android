package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.capability.usb.UsbBroker
import com.vyzorix.capability.usb.UsbError
import com.vyzorix.capability.usb.UsbResult
import org.json.JSONObject

/**
 * Opens an attached USB device and holds its file descriptor.
 *
 * This is the write half of USB: it claims a hardware device, so it carries
 * `op:write` — the first API in the catalogue to do so. The fd it opens is the
 * capability the container later receives: the
 * Debian-side tool drives the device through that descriptor, and `usb_detach`
 * revoking it by closing is what revokes access.
 *
 * `protection:signature` matches the server catalog: the capability is granted by
 * a role with the USB action, not by a runtime Android permission dialog. What the
 * app *does* need is the platform's per-device USB permission, which is a
 * user-granted dialog raised from the app — a missing grant is reported as a
 * failed open, not as an internal error.
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
class UsbAttachApi(
    private val broker: UsbBroker,
) : ApiHandler {

    override fun handle(call: ApiCall): ApiOutcome {
        val name = call.args["name"]?.trim().orEmpty()
        if (name.isEmpty()) {
            return ApiOutcome.fail(BridgeErrorKind.INVALID_ARGS, "name is required")
        }

        return when (val result = broker.attach(name)) {
            is UsbResult.Ok ->
                ApiOutcome.ok(
                    JSONObject()
                        .put("name", name)
                        .put("fd", result.value)
                        .put("attached", true),
                )
            is UsbResult.Err -> ApiOutcome.fail(mapKind(result.error), result.error.detail)
        }
    }

    /**
     * A USB failure kind to a bridge error kind.
     *
     * `OPEN_FAILED` maps to `INVALID_ARGS`, not `INTERNAL`: the name did not resolve
     * to an attached, permitted device, which is a caller-side condition (a wrong
     * name, or a permission the operator has not granted), not a broken handler.
     * Reporting it as internal would make the smoke test — and an operator — read a
     * missing device as a bug.
     */
    private fun mapKind(error: UsbError): String =
        when (error.kind) {
            UsbError.Kind.NO_SUCH_DEVICE, UsbError.Kind.OPEN_FAILED, UsbError.Kind.TOO_MANY_OPEN ->
                BridgeErrorKind.INVALID_ARGS
            else -> BridgeErrorKind.INTERNAL
        }

    companion object {
        const val NAME = "usb_attach"
    }
}
