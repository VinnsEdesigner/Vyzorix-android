package com.vyzorix.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.engine.ProotLauncher
import com.vyzorix.service.TerminalShellPolicy
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which shell a terminal gets, and when.
 *
 * This is the most consequential branch in the terminal path and, until now, the only
 * one with no test: the service picks the container's proot shell when the engine is
 * ready and falls back to a plain device shell when it is not. The instrumented PTY
 * tests never reached it — they hand the broker an executable directly — so a change
 * that inverted the branch, or made `isReady()` true too early, would have been
 * invisible.
 *
 * `isReady()` is three filesystem checks, so the conditions can be staged for real
 * rather than mocked: the test creates the files the launcher looks for.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalShellSelectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var rootfs: File
    private lateinit var prootBinary: File

    @Before
    fun stage() {
        // The policy is a static holder; a previous test may have left it on.
        TerminalShellPolicy.allowUncontainedShell(false)

        rootfs = ProotLauncher.rootfs(context)
        prootBinary = ProotLauncher.prootBinary(context)

        // Start from a clean slate: no engine.
        rootfs.deleteRecursively()
        prootBinary.delete()
    }

    @After
    fun cleanUp() {
        TerminalShellPolicy.allowUncontainedShell(false)
        rootfs.deleteRecursively()
        prootBinary.delete()
    }

    /** Makes `isReady()` true: an executable proot and a rootfs with bash in it. */
    private fun installEngine() {
        prootBinary.parentFile?.mkdirs()
        prootBinary.writeText("#!/bin/sh\n")
        prootBinary.setExecutable(true, true)

        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/bash").writeText("#!/bin/sh\n")

        // The scratch dir proot needs; isReady() requires it.
        ProotLauncher.tmpDir(context).mkdirs()
    }

    @Test
    fun withoutTheEngineTheShellIsNotTheContainer() {
        assertFalse(
            "a fresh context must not report the engine ready",
            ProotLauncher.isReady(context),
        )

        // The request the service would build in this state is the uncontained one.
        val request = ProotLauncher.uncontainedShellRequest(context, token = "t")
        assertEquals("/system/bin/sh", request.executable)
    }

    @Test
    fun withTheEngineTheShellIsProotInTheContainer() {
        installEngine()
        assertTrue("the engine must report ready once staged", ProotLauncher.isReady(context))

        val request = ProotLauncher.loginShellRequest(context, token = "t")

        // The executable is proot, not a shell: proot is what makes the session a
        // container rather than a shell running as the app's uid.
        assertEquals(prootBinary.absolutePath, request.executable)
        assertTrue("proot must be told where the rootfs is", request.arguments.contains("-r"))
        assertTrue(
            "the rootfs path must be the argument after -r",
            request.arguments[request.arguments.indexOf("-r") + 1] == rootfs.absolutePath,
        )
    }

    @Test
    fun theEngineIsNotReadyWithoutBash() {
        installEngine()

        // Remove the one file the launcher checks. A rootfs without bash is not a
        // rootfs the engine can start, and `isReady()` must say so rather than opening
        // a container that dies at exec.
        File(rootfs, "bin/bash").delete()

        assertFalse("a rootfs without bin/bash is not ready", ProotLauncher.isReady(context))
    }

    @Test
    fun theEngineIsNotReadyWithoutTheScratchDir() {
        installEngine()

        // proot fails with "can't create temporary file" without this, so a ready
        // engine that cannot start is exactly what isReady() must not report.
        //
        // Deleted via tmpDirPath, not tmpDir: tmpDir creates the directory, so using
        // it here would stage the condition and then repair it.
        ProotLauncher.tmpDirPath(context).deleteRecursively()

        assertFalse("a missing proot scratch dir means not ready", ProotLauncher.isReady(context))
    }

    @Test
    fun theUncontainedShellIsOffByDefault() {
        // The fallback runs as the app's uid and can read the app's private files, so
        // it must never be the default. A build has to ask for it.
        assertFalse(
            "the uncontained shell must be off unless explicitly allowed",
            TerminalShellPolicy.isUncontainedShellAllowed(),
        )
    }
}
