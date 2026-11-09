package com.vyzorix.connector.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The static rules the device re-checks on a verified config
 * (VYZORIX_DEPLOY_CONTRACT.md §5.3, §8). The cases mirror the server's own refusal
 * tests, because the two checks are the same rule and must agree.
 */
class ConfigValidatorTest {
    private val validator = ConfigValidator()

    private fun app(
        cmd: String = "/home/apps/vfd/run.sh",
        artifacts: List<ArtifactConfig> = listOf(artifact()),
    ) = AppConfig(
        name = "vfd",
        dir = "/home/apps/vfd",
        cmd = cmd,
        port = 8080,
        autostart = true,
        expose = "loopback",
        purge = false,
        env = emptyMap(),
        secretNames = emptyList(),
        artifacts = artifacts,
        pwa = false,
        pwaOverrides = emptyMap(),
    )

    private fun artifact(sha256: String = "9f2c") =
        ArtifactConfig(
            name = "engine",
            arch = null,
            url = "https://x/y.tar.gz",
            sha256 = sha256,
            kind = "dir",
        )

    @Test
    fun `a well-formed app passes`() {
        assertNull(validator.validate(listOf(app())))
    }

    @Test
    fun `a reference without sha256 is refused and names the artifact`() {
        val refusal = validator.validate(listOf(app(artifacts = listOf(artifact(sha256 = "")))))

        assertEquals("unhashed_reference", refusal?.reason)
        assertEquals(true, refusal?.detail?.contains("engine"))
    }

    @Test
    fun `a cmd outside the app directory is refused`() {
        val refusal = validator.validate(listOf(app(cmd = "/home/apps/wiki/run.sh")))

        assertEquals("cmd_escape", refusal?.reason)
    }

    @Test
    fun `a cmd that traverses out of the app directory is refused`() {
        val refusal = validator.validate(listOf(app(cmd = "/home/apps/vfd/../../bin/sh")))

        assertEquals("cmd_escape", refusal?.reason)
    }

    @Test
    fun `a cmd with a shell metacharacter is refused`() {
        for (
        cmd in
        listOf(
            "/home/apps/vfd/run.sh; rm -rf /",
            "/home/apps/vfd/run.sh && whoami",
            "/home/apps/vfd/\$(id).sh",
            "/home/apps/vfd/run.sh | tee /tmp/x",
        )
        ) {
            assertEquals("cmd_metacharacter", validator.validate(listOf(app(cmd = cmd)))?.reason)
        }
    }

    @Test
    fun `a relative cmd is refused`() {
        val refusal = validator.validate(listOf(app(cmd = "run.sh")))

        assertEquals("cmd_not_absolute", refusal?.reason)
    }

    @Test
    fun `an app with no artifacts passes the reference check`() {
        assertNull(validator.validate(listOf(app(artifacts = emptyList()))))
    }

    @Test
    fun `an invalid pwa display is refused`() {
        val bad = app().copy(pwa = true, pwaOverrides = mapOf("display" to "kiosk"))

        assertEquals("invalid_display", validator.validate(listOf(bad))?.reason)
    }

    @Test
    fun `a valid pwa display passes`() {
        val good = app().copy(pwa = true, pwaOverrides = mapOf("display" to "fullscreen"))

        assertNull(validator.validate(listOf(good)))
    }
}
