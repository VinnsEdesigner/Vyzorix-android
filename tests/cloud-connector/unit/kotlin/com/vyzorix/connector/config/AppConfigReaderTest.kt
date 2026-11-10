package com.vyzorix.connector.config

import com.vyzorix.libv.VParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The apps reader is the piece an earlier draft could not pass: the document is a
 * strict depth tree with no list syntax, so apps are a repeated node and a reader
 * that flattens the tree by absolute path mixes them up. These tests pin the
 * per-app reading against a two-app document.
 */
class AppConfigReaderTest {
    private val reader = AppConfigReader()

    /** The example: two apps, each with its own fields. */
    private fun twoAppDocument(): String =
        """
        ' vyzorix.v — device configuration
        v device d_01H
        vv org org_01H
        vv name field-ops-07
        vv tier 2
        vv features
        vvv terminal true
        vvv usb false
        vvv apps true
        vv apps
        vvv app vfd
        vvvv dir /home/apps/vfd
        vvvv cmd /home/apps/vfd/run.sh
        vvvv port 8080
        vvvv autostart true
        vvvv expose loopback
        vvvv purge false
        vvvv env MI_PORT=8080
        vvvv secret DATABASE_URL
        vvvv secret S3_SECRET_KEY
        vvvv artifact engine
        vvvvv arch arm64-v8a
        vvvvv url https://example.invalid/vyzor-arm64.tar.gz
        vvvvv sha256 9f2c
        vvvvv kind dir
        vvvv artifact console
        vvvvv url https://example.invalid/console.tar.gz
        vvvvv sha256 41ab
        vvvvv kind webroot
        vvvv pwa true
        vvvvv theme_color #be123c
        vvvvv display standalone
        vvv app wiki
        vvvv dir /home/apps/wiki
        vvvv cmd /home/apps/wiki/run.sh
        vvvv port 9000
        vvvv expose lan
        vvvv artifact site
        vvvvv url https://example.invalid/wiki.tar.gz
        vvvvv sha256 7b3d
        vvvvv kind webroot
        v version 7
        """.trimIndent() + "\n"

    private fun read(document: String): List<AppConfig> = reader.read(VParser.parse(document).root)

    @Test
    fun `reads both apps in document order`() {
        val apps = read(twoAppDocument())

        assertEquals(2, apps.size)
        assertEquals(listOf("vfd", "wiki"), apps.map { it.name })
    }

    @Test
    fun `each app carries its own fields, not a flattened mix`() {
        val vfd = read(twoAppDocument()).first { it.name == "vfd" }
        val wiki = read(twoAppDocument()).first { it.name == "wiki" }

        assertEquals("/home/apps/vfd/run.sh", vfd.cmd)
        assertEquals(8080, vfd.port)
        assertEquals("loopback", vfd.expose)
        assertEquals(false, vfd.purge)

        assertEquals("/home/apps/wiki/run.sh", wiki.cmd)
        assertEquals(9000, wiki.port)
        assertEquals("lan", wiki.expose)
        // The renderer omits autostart=false only; an absent node defaults true.
        assertEquals(true, wiki.autostart)
    }

    @Test
    fun `reads env pairs by splitting on the first equals`() {
        val vfd = read(twoAppDocument()).first { it.name == "vfd" }

        assertEquals(mapOf("MI_PORT" to "8080"), vfd.env)
    }

    @Test
    fun `reads secret names, never values`() {
        val vfd = read(twoAppDocument()).first { it.name == "vfd" }

        assertEquals(listOf("DATABASE_URL", "S3_SECRET_KEY"), vfd.secretNames)
    }

    @Test
    fun `reads artifacts, including the per-arch field`() {
        val vfd = read(twoAppDocument()).first { it.name == "vfd" }

        assertEquals(listOf("engine", "console"), vfd.artifacts.map { it.name })
        assertEquals("arm64-v8a", vfd.artifacts.first().arch)
        assertEquals("dir", vfd.artifacts.first().kind)
        assertEquals("webroot", vfd.artifacts[1].kind)
        // An artifact with no `arch` applies to every ABI.
        assertEquals(null, vfd.artifacts[1].arch)
    }

    @Test
    fun `reads the pwa boolean and its table overrides`() {
        val vfd = read(twoAppDocument()).first { it.name == "vfd" }
        val wiki = read(twoAppDocument()).first { it.name == "wiki" }

        assertEquals(true, vfd.pwa)
        assertEquals("#be123c", vfd.pwaOverrides["theme_color"])
        assertEquals("standalone", vfd.pwaOverrides["display"])

        // A webroot is not a PWA unless it says so — the default is false.
        assertEquals(false, wiki.pwa)
        assertEquals(emptyMap<String, String>(), wiki.pwaOverrides)
    }

    @Test
    fun `a config with no apps section reads as no apps`() {
        val body = "v device d_1\nvv org o_1\nv version 1\n"

        assertEquals(emptyList<AppConfig>(), read(body))
    }
}
