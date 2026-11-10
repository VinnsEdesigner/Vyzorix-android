package com.vyzorix.agent

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyzorix.container.ProotLauncher
import com.vyzorix.service.RootfsBootstrap
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * proot runs from the app's own process, not just from a shell.
 *
 * This is the requirement everything else rests on, and it is easy to test the wrong
 * way: `adb shell run-as ... sh -c 'proot ...'` runs proot as a child of `sh`, which
 * is a plain process. The container launches it from the app's ART process, which is a
 * different environment — a zygote-forked VM with its own fds and SELinux domain —
 * and that is the case that has to work.
 *
 * The failure this guards against was real: proot exited **255 with no output** when
 * launched from the ART process, while the identical command succeeded from a shell.
 * A test that shells out would have passed and proved nothing.
 *
 * Non-PTY on purpose: this isolates "can the app launch proot at all" from the PTY,
 * so a failure here is unambiguous.
 */
@RunWith(AndroidJUnit4::class)
class ProotFromAppProcessTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun install() {
        RootfsBootstrap.ensureInstalled(context)
    }

    @Test
    fun prootRunsFromTheAppProcess() {
        assertTrue("the container must be installed", ProotLauncher.isReady(context))

        val rootfs = ProotLauncher.rootfs(context).absolutePath
        val proot = ProotLauncher.prootBinary(context).absolutePath
        val tmp = ProotLauncher.tmpDir(context).absolutePath

        val command =
            listOf(
                proot,
                "--link2symlink",
                "-0",
                "-r",
                rootfs,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-w",
                "/root",
                "/bin/bash",
                "-c",
                "cat /etc/debian_version; id -u",
            )

        // proot writes its loader to a temp file before every exec, and without
        // PROOT_TMP_DIR it fails with "can't create temporary file" — the app cannot
        // write the default /data/local/tmp. PATH is set for the same reason
        // loginShellRequest sets it: the container's bash is started directly, not
        // through a login shell, so without it `cat` and `id` are not found. Both
        // mirror what production puts in the child's environment.
        val environment =
            mapOf(
                "PROOT_TMP_DIR" to tmp,
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            )

        val builder = ProcessBuilder(command).redirectErrorStream(true)
        environment.forEach { (key, value) -> builder.environment()[key] = value }

        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()

        assertTrue(
            "proot must run from the app's own process; exit=$code output=${out.take(500)}",
            out.contains(DEBIAN_PREFIX) && out.contains("0"),
        )
    }

    private companion object {
        /** Debian 12.x — the suite the rootfs is built from. */
        const val DEBIAN_PREFIX = "12."
    }
}
