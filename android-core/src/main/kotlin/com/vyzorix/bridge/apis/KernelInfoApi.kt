package com.vyzorix.bridge.apis

import android.os.Build
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Kernel and OS identity. Read from `System` properties and `Build`, which is
 * what the platform exposes to an app; `uname(2)` is not reachable from here
 * without a syscall shim. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object KernelInfoApi : ApiHandler {

    const val NAME = "kernel_info"

    override fun handle(call: ApiCall): ApiOutcome =
        ApiOutcome.ok(
            JSONObject()
                .put("osName", System.getProperty("os.name").orEmpty())
                .put("osVersion", System.getProperty("os.version").orEmpty())
                .put("javaVm", System.getProperty("java.vm.version").orEmpty())
                .put("kernel", System.getProperty("os.version").orEmpty())
                .put("release", Build.VERSION.RELEASE)
                .put("codename", Build.VERSION.CODENAME)
                .put("incremental", Build.VERSION.INCREMENTAL),
        )
}
