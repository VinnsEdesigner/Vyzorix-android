package com.vyzorix.bridge.apis

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * One package's details, by name. The single-package form of `installed_apps`, for
 * an operator asking about one thing rather than enumerating. No runtime
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
object PackageInfoApi : ApiHandler {

    const val NAME = "package_info"

    override fun handle(call: ApiCall): ApiOutcome {
        val target =
            call.args["package"]
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INVALID_ARGS,
                    "package is required",
                )

        val info =
            try {
                call.context.packageManager.getPackageInfo(target, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return ApiOutcome.fail(
                    BridgeErrorKind.INVALID_ARGS,
                    "no package named $target (${e.message})",
                )
            }

        return ApiOutcome.ok(
            JSONObject()
                .put("packageName", info.packageName)
                .put("versionName", info.versionName.orEmpty())
                .put("versionCode", versionCodeOf(info))
                .put("firstInstallMs", info.firstInstallTime)
                .put("lastUpdateMs", info.lastUpdateTime)
                .put("enabled", info.applicationInfo?.enabled ?: false),
        )
    }

/**
     * The package's version code as a Long.
     *
     * `PackageInfo.longVersionCode` is API 28 and `minSdk` is 26, so calling it
     * unguarded is a `NoSuchMethodError` on a 26 or 27 device — found by the smoke test,
     * which is exactly the class of bug it exists for. `versionCode` is an Int and
     * available from API 1, so it is the fallback; a version code above Int.MAX_VALUE is
     * theoretical for a sideloaded app and would be truncated rather than crash.
     */
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
}
