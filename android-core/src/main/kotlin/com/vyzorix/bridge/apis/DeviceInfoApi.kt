package com.vyzorix.bridge.apis

import android.os.Build
import android.os.SystemClock
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Device identity and platform facts. No runtime permission.
 *
 * This is the "what device is this" summary: the hardware identity (model, device,
 * product, board, SoC), the platform version, the ABI list and the runtime package.
 * The *build* identity — fingerprint, build id, display, tags — belongs to
 * `build_fingerprint`, so it is not repeated here; `product`, `device` and `brand`
 * appear in both because they are genuinely part of both a device's identity and its
 * build, and a caller asking "what device" should not have to read two APIs for them.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object DeviceInfoApi : ApiHandler {

    const val NAME = "device_info"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val fields =
            JSONObject()
                // Platform version.
                .put("model", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("apiLevel", Build.VERSION.SDK_INT)
                .put("codename", Build.VERSION.CODENAME)
                .put("incremental", Build.VERSION.INCREMENTAL)
                .put("securityPatch", Build.VERSION.SECURITY_PATCH.orEmpty())
                // Hardware identity. These are the `ro.product.*` values, distinct
                // from the marketing model name, and are what an operator uses to
                // tell two devices of the same model apart.
                .put("device", Build.DEVICE)
                .put("product", Build.PRODUCT)
                .put("brand", Build.BRAND)
                .put("board", Build.BOARD)
                .put("hardware", Build.HARDWARE)
                .put("bootloader", Build.BOOTLOADER)
                .put("buildHost", Build.HOST)
                // ABI lists: the full preference order plus the split, so a caller can
                // tell whether the device is 64-bit capable without parsing.
                .put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))
                .put("supported32BitAbis", Build.SUPPORTED_32_BIT_ABIS.joinToString(","))
                .put("supported64BitAbis", Build.SUPPORTED_64_BIT_ABIS.joinToString(","))
                // Runtime.
                .put("uptimeMs", SystemClock.elapsedRealtime())
                .put("packageName", context.packageName)

        // The SoC fields are API 31+; below that they do not exist and an unguarded
        // read is a NoSuchFieldError on a 26-30 device. Empty is the honest answer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fields
                .put("socManufacturer", Build.SOC_MANUFACTURER.orEmpty())
                .put("socModel", Build.SOC_MODEL.orEmpty())
        } else {
            fields
                .put("socManufacturer", "")
                .put("socModel", "")
        }

        return ApiOutcome.ok(fields)
    }
}
