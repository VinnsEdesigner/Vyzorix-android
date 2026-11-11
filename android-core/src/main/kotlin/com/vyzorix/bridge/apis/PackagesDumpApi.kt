package com.vyzorix.bridge.apis

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * A full dump of every package on the device: its name, its components, its exact
 * on-disk folders, and how it relates to the other packages.
 *
 * ## Parent and children
 *
 * There is no literal "parent package" in Android, but two relationships are real and
 * derivable, and both are reported:
 *
 *  - **Shared user id** (`android:sharedUserId`) — packages declaring the same shared
 *    user id run under **one uid**. That is the actual "parent" grouping: everything
 *    sharing a uid is one application from the kernel's point of view. Each package
 *    reports its `sharedUserId`, and a `sharedUidGroups` section lists every group with
 *    its members, so a caller sees the family without re-deriving it.
 *  - **APK splits** (`splitNames`) — a modern app ships as a base APK plus split APKs
 *    (per-ABI, per-density). Those are the package's literal *children* on disk, and
 *    each split's own path is reported.
 *
 * ## Exact folders
 *
 * `dataDir`, `deviceProtectedDataDir`, `sourceDir`, `publicSourceDir`,
 * `nativeLibraryDir` and each split's source dir are the real paths, reported
 * verbatim. These are what an operator needs to find a package on disk; none is
 * derivable from the package name.
 *
 * ## Bounds
 *
 * A device has hundreds of packages and thousands of components, so the dump is
 * large. `components` (default true) adds the component names; `limit` caps the
 * package count, and `truncated` says whether it was hit.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object PackagesDumpApi : ApiHandler {

    const val NAME = "packages_dump"

    /** How many packages to include when the caller does not ask for a different cap. */
    private const val DEFAULT_LIMIT = 1000

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val pm = context.packageManager

        val includeComponents = call.args["components"]?.toBooleanStrictOrNull() ?: true
        val limit = call.args["limit"]?.toIntOrNull()?.coerceIn(1, DEFAULT_LIMIT) ?: DEFAULT_LIMIT

        val all =
            try {
                pm.getInstalledPackages(matchFlags(includeComponents))
            } catch (e: Exception) {
                return ApiOutcome.ok(
                    JSONObject()
                        .put("error", "getInstalledPackages failed: ${e.message}")
                        .put("packages", JSONArray())
                        .put("count", 0),
                )
            }

        val sorted = all.sortedBy { it.packageName }
        val page = sorted.take(limit)

        val array = JSONArray()
        for (info in page) {
            array.put(packageJson(pm, info, includeComponents))
        }

        return ApiOutcome.ok(
            JSONObject()
                .put("packages", array)
                .put("count", array.length())
                .put("totalAvailable", sorted.size)
                .put("truncated", sorted.size > page.size)
                .put("ownPackage", context.packageName)
                .put("sharedUidGroups", sharedUidGroups(sorted))
                .put("matchFlags", matchFlags(includeComponents)),
        )
    }

    /**
     * Packages grouped by the uid they share.
     *
     * A group with more than one member is a real family: those packages run as one
     * application. This is the "parent/children" view an operator asked for, presented
     * as groups rather than forcing each package to name a parent it does not have.
     */
    private fun sharedUidGroups(packages: List<PackageInfo>): JSONArray {
        val byUid = linkedMapOf<Int, MutableList<String>>()

        for (info in packages) {
            val uid = info.applicationInfo?.uid ?: continue
            byUid.getOrPut(uid) { mutableListOf() }.add(info.packageName)
        }

        val groups = JSONArray()
        for ((uid, members) in byUid) {
            // Only report actual groups; a uid with one package is not a family.
            if (members.size < 2) continue

            groups.put(
                JSONObject()
                    .put("uid", uid)
                    .put("count", members.size)
                    .put("members", JSONArray(members.sorted())),
            )
        }

        return groups
    }

    /** The match flags for the broadest listing this build supports. */
    private fun matchFlags(includeComponents: Boolean): Int {
        // The base: everything, including retained-after-uninstall packages.
        var flags = PackageManager.MATCH_UNINSTALLED_PACKAGES

        if (includeComponents) {
            flags = flags or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_META_DATA
        }

        // APEX modules are API 29+. minSdk is 26, so guard it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            flags = flags or PackageManager.MATCH_APEX
        }

        return flags
    }

    /** One package: its name, folders, family and components. */
    private fun packageJson(
        pm: PackageManager,
        info: PackageInfo,
        includeComponents: Boolean,
    ): JSONObject {
        val app = info.applicationInfo

        val json =
            JSONObject()
                .put("packageName", info.packageName)
                .put("versionName", info.versionName.orEmpty())
                .put("versionCode", versionCodeOf(info))
                .put("firstInstallTime", info.firstInstallTime)
                .put("lastUpdateTime", info.lastUpdateTime)
                .put("flags", app?.flags ?: 0)
                .put("system", flagSet(app, ApplicationInfo.FLAG_SYSTEM))
                .put("updatedSystem", flagSet(app, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                .put("debuggable", flagSet(app, ApplicationInfo.FLAG_DEBUGGABLE))
                .put("stopped", flagSet(app, ApplicationInfo.FLAG_STOPPED))
                .put("enabled", app?.enabled ?: false)
                .put("label", labelOf(pm, app))

        if (app != null) {
            json
                .put("uid", app.uid)
                .put("sharedUserId", info.sharedUserId.orEmpty())
                .put("targetSdk", app.targetSdkVersion)
                .put("minSdk", app.minSdkVersion)
                // The exact on-disk folders, verbatim.
                .put("dataDir", app.dataDir.orEmpty())
                .put("deviceProtectedDataDir", app.deviceProtectedDataDir.orEmpty())
                .put("sourceDir", app.sourceDir.orEmpty())
                .put("publicSourceDir", app.publicSourceDir.orEmpty())
                .put("nativeLibraryDir", app.nativeLibraryDir.orEmpty())
                // "Privileged" is not readable: ApplicationInfo.FLAG_PRIVILEGED is
                // @hide. It *is* derivable, because a privileged app is one installed
                // under /system/priv-app (or the newer /system/apex), so the path is the
                // honest signal rather than a guess at a hidden flag.
                .put("privileged", isPrivileged(app.sourceDir))
                .put("splitCount", info.splitNames?.size ?: 0)
                .put("splitNames", JSONArray(info.splitNames?.toList().orEmpty()))
                .put("splitSourceDirs", JSONArray(app.splitSourceDirs?.toList().orEmpty()))
        }

        if (includeComponents) {
            json
                .put("activityCount", info.activities?.size ?: 0)
                .put("serviceCount", info.services?.size ?: 0)
                .put("receiverCount", info.receivers?.size ?: 0)
                .put("providerCount", info.providers?.size ?: 0)
                .put("activities", names(info.activities))
                .put("services", names(info.services))
                .put("receivers", names(info.receivers))
                .put("providers", names(info.providers))
                .put(
                    "requestedPermissions",
                    JSONArray(info.requestedPermissions?.toList().orEmpty()),
                )
        }

        return json
    }

    /** The component class names as a JSON array. */
    private fun names(components: Array<out android.content.pm.ComponentInfo>?): JSONArray {
        val array = JSONArray()

        components.orEmpty().forEach { array.put(it.name) }

        return array
    }

    private fun flagSet(
        app: ApplicationInfo?,
        flag: Int,
    ): Boolean = (app?.flags ?: 0) and flag != 0

    /**
     * True when the app is installed in a privileged location.
     *
     * A privileged app is one the system grants extra permissions, and the platform
     * marks it with `ApplicationInfo.FLAG_PRIVILEGED` — which is `@hide`, so it cannot
     * be read. The install path is the same fact: privileged apps live under
     * `/system/priv-app`, and the APEX modules under `/system/apex` are privileged too.
     * Deriving from the path is honest, not a guess at a hidden flag.
     */
    private fun isPrivileged(sourceDir: String?): Boolean {
        val dir = sourceDir.orEmpty()

        return dir.startsWith("/system/priv-app/") ||
            dir.startsWith("/system/apex/") ||
            dir.startsWith("/apex/")
    }

    /** The app's display label, or "" when it cannot be loaded. */
    private fun labelOf(
        pm: PackageManager,
        app: ApplicationInfo?,
    ): String = runCatching { app?.loadLabel(pm)?.toString() }.getOrNull().orEmpty()

    /**
     * The package's version code as a Long.
     *
     * `longVersionCode` is API 28 and `minSdk` is 26, so the deprecated `versionCode`
     * is the fallback — the same guard `InstalledAppsApi` uses.
     */
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
}
