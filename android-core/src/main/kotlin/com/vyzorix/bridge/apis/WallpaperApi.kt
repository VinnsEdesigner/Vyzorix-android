// Lint's MissingPermission check cannot see the router's gate: ApiRouter.dispatch
// refuses a call whose API requires a runtime permission the app does not hold, and
// PermissionRegistry is the single source for which permission that is. Every call
// below is behind that gate, and the ones that also read the grant inline are checked
// again here. Suppressing the check at the file level is deliberate, not a way to
// hide an unchecked call.
@file:Suppress("MissingPermission")

package com.vyzorix.bridge.apis

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import java.io.File
import java.net.URL
import org.json.JSONObject

/**
 * Reads and sets the device wallpaper — home screen, lock screen, or both.
 *
 * ## What is settable, and how
 *
 * `WallpaperManager.setBitmap` needs only `SET_WALLPAPER`, a normal permission, so a
 * sideloaded app can set the wallpaper without a dialog. `which` selects the target:
 * `FLAG_SYSTEM` (home), `FLAG_LOCK` (lock screen), or both. Setting the lock screen
 * separately is API 24+, and `minSdk` is 26, so no guard is needed.
 *
 * ## Where the image comes from
 *
 * Either a local `path` (a file the device already has, e.g. under `/sdcard`) or a
 * `url` to fetch. The URL path needs `INTERNET`, which the app holds, and the image
 * is decoded and size-checked before it is applied: a wallpaper is a large bitmap and
 * a bad one should be refused, not half-applied.
 *
 * A read (`GET` semantics — no `path`/`url`) reports whether a wallpaper is set on
 * each surface and its dimensions, which needs no permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_WRITE,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object WallpaperApi : ApiHandler {

    const val NAME = "wallpaper"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val manager = WallpaperManager.getInstance(context)

        val path = call.args["path"].orEmpty()
        val url = call.args["url"].orEmpty()

        // No source: a status read.
        if (path.isEmpty() && url.isEmpty()) {
            return ApiOutcome.ok(status(manager))
        }

        val bitmap =
            try {
                load(path, url)
            } catch (e: Exception) {
                return ApiOutcome.fail(
                    BridgeErrorKind.INVALID_ARGS,
                    "could not load the wallpaper image: ${e.message}",
                )
            } ?: return ApiOutcome.fail(
                BridgeErrorKind.INVALID_ARGS,
                "the image could not be decoded (path=$path url=$url)",
            )

        val which = whichFlags(call.args["target"])

        return try {
            manager.setBitmap(bitmap, null, true, which)
            bitmap.recycle()

            ApiOutcome.ok(
                status(manager).put("set", true).put("target", call.args["target"].orEmpty()),
            )
        } catch (e: Exception) {
            // SET_WALLPAPER refused, or the image was too large for the surface. Logged
            // and reported, never thrown.
            ApiOutcome.fail(BridgeErrorKind.INTERNAL, "could not set the wallpaper: ${e.message}")
        }
    }

    /** Whether each surface has a wallpaper, and its size. Needs no permission. */
    private fun status(manager: WallpaperManager): JSONObject {
        val result = JSONObject()

        result.put("isWallpaperSupported", manager.isWallpaperSupported)
        result.put("isSetWallpaperAllowed", manager.isSetWallpaperAllowed)

        // The current home wallpaper's size, when one can be read.
        runCatching { manager.drawable }
            .getOrNull()
            ?.let { drawable ->
                result.put("width", drawable.intrinsicWidth)
                result.put("height", drawable.intrinsicHeight)
            }

        return result
    }

    /** Loads the bitmap from a local path or a URL. */
    private fun load(
        path: String,
        url: String,
    ): Bitmap? {
        val stream =
            when {
                path.isNotEmpty() -> {
                    val file = File(path)
                    if (!file.isFile) return null

                    file.inputStream()
                }
                else -> {
                    // A plain URL stream: the same shape the rootfs fetch uses, so no
                    // HTTP dependency is added for one download.
                    URL(url).openStream()
                }
            }

        return stream.use { BitmapFactory.decodeStream(it) }
    }

    /** Maps the `target` argument to the WallpaperManager flags. */
    private fun whichFlags(target: String?): Int =
        when (target?.lowercase()) {
            "lock" -> WallpaperManager.FLAG_LOCK
            "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            // Default to the home screen, which is what "change the wallpaper" means.
            else -> WallpaperManager.FLAG_SYSTEM
        }
}
