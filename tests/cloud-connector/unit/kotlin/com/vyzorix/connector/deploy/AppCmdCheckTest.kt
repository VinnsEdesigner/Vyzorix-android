package com.vyzorix.connector.deploy

import com.vyzorix.connector.config.AppConfig
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The device-side `cmd` re-check (VYZORIX_DEPLOY_CONTRACT.md §8 rules 3-4). The case
 * that matters is a symlink inside the app directory that points out of it: the server
 * cannot see it, so the device is the only place it can be caught.
 */
class AppCmdCheckTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun app(cmd: String) =
        AppConfig(
            name = "vfd",
            dir = "/home/apps/vfd",
            cmd = cmd,
            port = 8080,
            autostart = true,
            expose = "loopback",
            purge = false,
            env = emptyMap(),
            secretNames = emptyList(),
            artifacts = emptyList(),
            pwa = false,
            pwaOverrides = emptyMap(),
        )

    private fun executable(
        dir: File,
        name: String,
    ): File =
        File(dir, name).apply {
            writeText("#!/bin/sh\n")
            setExecutable(true, true)
        }

    @Test
    fun `an executable inside the app directory passes`() {
        val appDir = folder.newFolder("vfd")
        executable(appDir, "run.sh")

        assertNull(AppCmdCheck.check(app("/home/apps/vfd/run.sh"), appDir))
    }

    @Test
    fun `a symlink that points outside the app directory is refused`() {
        val appDir = folder.newFolder("vfd")
        val outside = folder.newFile("outside.sh").apply { setExecutable(true, true) }
        Files.createSymbolicLink(File(appDir, "run.sh").toPath(), outside.toPath())

        assertEquals("cmd_escape", AppCmdCheck.check(app("/home/apps/vfd/run.sh"), appDir))
    }

    @Test
    fun `a missing cmd is refused as not executable`() {
        val appDir = folder.newFolder("vfd")

        assertEquals("cmd_not_executable", AppCmdCheck.check(app("/home/apps/vfd/run.sh"), appDir))
    }

    @Test
    fun `a non-executable file is refused`() {
        val appDir = folder.newFolder("vfd")
        File(appDir, "run.sh").apply {
            writeText("not executable")
            setExecutable(false, false)
        }

        assertEquals("cmd_not_executable", AppCmdCheck.check(app("/home/apps/vfd/run.sh"), appDir))
    }
}
