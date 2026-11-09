package com.vyzorix.connector.deploy

import com.vyzorix.connector.config.AppConfig
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The `site.webmanifest` a webroot gets when it declares `pwa = true`
 * (VYZORIX_DEPLOY_CONTRACT.md §6).
 *
 * A webroot is **not** a PWA unless it says so: `pwa` absent or `false` generates
 * nothing. A manifest changes how a browser presents and installs a page, so an app
 * that is not a PWA must not be handed one by accident, and the default is `false`.
 *
 * Only fields the operator overrode are written; the rest are the defaults below.
 * Icons are not generated — an icon is an image the platform does not invent — so an
 * app with no icons is installable with a default icon rather than referencing a file
 * that does not exist.
 *
 * The file is written into the webroot **before the app starts**, so the app serves
 * it as a static file; nothing about the artifact's own bytes changes.
 */
class PwaManifest(
    private val json: Json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        },
) {
    /**
     * Writes the manifest into [appDir]'s webroot, or does nothing when the app is
     * not a PWA or has no webroot artifact.
     */
    fun write(
        app: AppConfig,
        appDir: File,
    ) {
        if (!app.pwa) return

        val webroot = app.artifacts.firstOrNull { it.kind == WEBROOT } ?: return
        val directory = File(File(appDir, webroot.name), WEBROOT_DIR)
        directory.mkdirs()

        File(directory, MANIFEST_FILE).writeText(json.encodeToString(document(app)))
    }

    private fun document(app: AppConfig): PwaDocument =
        PwaDocument(
            name = app.pwaOverrides["name"] ?: app.name,
            shortName = app.pwaOverrides["short_name"] ?: app.name,
            startUrl = app.pwaOverrides["start_url"] ?: DEFAULT_START_URL,
            display = app.pwaOverrides["display"] ?: DEFAULT_DISPLAY,
            backgroundColor = app.pwaOverrides["background_color"] ?: DEFAULT_BACKGROUND,
            themeColor = app.pwaOverrides["theme_color"] ?: DEFAULT_THEME,
        )

    @Serializable
    private data class PwaDocument(
        val name: String,
        @kotlinx.serialization.SerialName("short_name") val shortName: String,
        @kotlinx.serialization.SerialName("start_url") val startUrl: String,
        val display: String,
        @kotlinx.serialization.SerialName("background_color") val backgroundColor: String,
        @kotlinx.serialization.SerialName("theme_color") val themeColor: String,
        val icons: List<String> = emptyList(),
    )

    companion object {
        const val MANIFEST_FILE = "site.webmanifest"
        const val WEBROOT = "webroot"
        const val WEBROOT_DIR = "www"

        private const val DEFAULT_START_URL = "/"
        private const val DEFAULT_DISPLAY = "standalone"
        private const val DEFAULT_BACKGROUND = "#ffffff"
        private const val DEFAULT_THEME = "#111111"
    }
}
