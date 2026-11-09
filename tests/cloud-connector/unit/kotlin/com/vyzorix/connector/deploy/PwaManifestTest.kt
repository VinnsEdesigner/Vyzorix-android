package com.vyzorix.connector.deploy

import com.vyzorix.connector.config.AppConfig
import com.vyzorix.connector.config.ArtifactConfig
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The PWA rule (VYZORIX_DEPLOY_CONTRACT.md §6). The negative case is the load-bearing
 * one: an app that is not a PWA must be handed no manifest at all.
 */
class PwaManifestTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val manifest = PwaManifest()

    private fun app(
        pwa: Boolean,
        overrides: Map<String, String> = emptyMap(),
        kind: String = "webroot",
    ) = AppConfig(
        name = "console",
        dir = "/home/apps/console",
        cmd = "/home/apps/console/run.sh",
        port = 8080,
        autostart = true,
        expose = "loopback",
        purge = false,
        env = emptyMap(),
        secretNames = emptyList(),
        artifacts =
            listOf(
                ArtifactConfig(
                    name = "site",
                    arch = null,
                    url = "https://x/y.tgz",
                    sha256 = "ab",
                    kind = kind,
                ),
            ),
        pwa = pwa,
        pwaOverrides = overrides,
    )

    private fun manifestFile(appDir: File) = File(appDir, "site/www/site.webmanifest")

    @Test
    fun `pwa false writes no manifest`() {
        val appDir = folder.newFolder("apps", "console")

        manifest.write(app(false), appDir)

        assertFalse(manifestFile(appDir).exists())
    }

    @Test
    fun `pwa true writes the defaults`() {
        val appDir = folder.newFolder("apps", "console")

        manifest.write(app(true), appDir)

        val text = manifestFile(appDir).readText()
        assertTrue(text.contains("\"name\": \"console\""))
        assertTrue(text.contains("\"display\": \"standalone\""))
        assertTrue(text.contains("\"start_url\": \"/\""))
        assertTrue(text.contains("\"icons\": []"))
    }

    @Test
    fun `an override replaces the default`() {
        val appDir = folder.newFolder("apps", "console")

        manifest.write(app(true, mapOf("theme_color" to "#be123c", "name" to "Vyzorix")), appDir)

        val text = manifestFile(appDir).readText()
        assertTrue(text.contains("\"theme_color\": \"#be123c\""))
        assertTrue(text.contains("\"name\": \"Vyzorix\""))
    }

    @Test
    fun `a webroot that is not pwa writes nothing even with overrides`() {
        val appDir = folder.newFolder("apps", "console")

        manifest.write(app(false, mapOf("theme_color" to "#be123c")), appDir)

        assertFalse(manifestFile(appDir).exists())
    }

    @Test
    fun `an app with no webroot artifact writes no manifest`() {
        val appDir = folder.newFolder("apps", "console")

        manifest.write(app(true, kind = "dir"), appDir)

        assertFalse(manifestFile(appDir).exists())
    }
}
