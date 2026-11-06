package com.vyzorix.bridge.apis

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * The packages installed on the device. Visible packages only: from Android 11 an
 * app sees a filtered list unless it holds QUERY_ALL_PACKAGES, so the count is
 * reported alongside the list rather than presented as everything. No runtime
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
object InstalledAppsApi : ApiHandler {

    const val NAME = "installed_apps"

    override fun handle(call: ApiCall): ApiOutcome {
        val packages = call.context.packageManager.getInstalledPackages(0)

        val array = JSONArray()
        for (info in packages.sortedBy { it.packageName }) {
            val flags = info.applicationInfo?.flags ?: 0
            val system = flags and ApplicationInfo.FLAG_SYSTEM != 0

            array.put(
                JSONObject()
                    .put("packageName", info.packageName)
                    .put("versionName", info.versionName.orEmpty())
                    .put("versionCode", versionCodeOf(info))
                    .put("system", system)
                    .put("enabled", info.applicationInfo?.enabled ?: false),
            )
        }

        return ApiOutcome.ok(
            JSONObject()
                .put("packages", array)
                .put("count", array.length())
                .put("ownPackage", call.context.packageName),
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
