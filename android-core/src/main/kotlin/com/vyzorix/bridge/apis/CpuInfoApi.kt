package com.vyzorix.bridge.apis

import android.os.Build
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * CPU and ABI facts, read from the platform rather than `/proc/cpuinfo`.
 *
 * `/proc/cpuinfo` is the obvious source and the wrong one: it is the host's, it
 * is not guaranteed readable from an app, and on an emulator it describes the
 * emulator. `Build` and `Runtime` report what the app is actually running on.
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
object CpuInfoApi : ApiHandler {

    const val NAME = "cpu_info"

    override fun handle(call: ApiCall): ApiOutcome {
        val runtime = Runtime.getRuntime()

        return ApiOutcome.ok(
            JSONObject()
                .put(
                    "abi",
                    Build.SUPPORTED_ABIS
                        .firstOrNull()
                        .orEmpty(),
                ).put(
                    "abis",
                    Build.SUPPORTED_ABIS
                        .joinToString(","),
                ).put(
                    "abi64",
                    Build.SUPPORTED_64_BIT_ABIS
                        .joinToString(","),
                ).put("cores", runtime.availableProcessors())
                .put("arch", System.getProperty("os.arch").orEmpty())
                .put("hardware", Build.HARDWARE)
                .put("board", Build.BOARD),
        )
    }
}
