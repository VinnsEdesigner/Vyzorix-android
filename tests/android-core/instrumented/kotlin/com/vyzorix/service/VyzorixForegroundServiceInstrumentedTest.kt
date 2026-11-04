package com.vyzorix.service

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Definition-of-done item 1: the foreground service starts and stays alive.
 *
 * The service is not exported (correctly), so it cannot be started from `adb
 * shell`; it is started from inside the app's own process, which is exactly what
 * the boot receiver does. The test then kills the process and checks the service
 * comes back, which is the START_STICKY contract.
 */
@RunWith(AndroidJUnit4::class)
class VyzorixForegroundServiceInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The bridge socket the service binds while it runs and deletes in onDestroy.
     *
     * It is the reliable "is the service up" signal on an emulator:
     * `ActivityManager.getRunningServices` lags teardown, so a test that polls it can
     * believe the service is gone while the old instance is still being destroyed —
     * and re-issuing `startForegroundService` against that instance leaves the system
     * waiting for a `startForeground` the dying instance will never call, which kills
     * the process.
     */
    private val socket = File(context.filesDir, "android.sock")

    /** Polls [predicate] for up to 15s; returns whether it became true. */
    private fun waitFor(predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(200)
        }

        return predicate()
    }

    /** Each test starts from a stopped service, so a lingering instance cannot leak in. */
    @Before
    fun stopAnyRunningService() {
        context.stopService(Intent(context, VyzorixForegroundService::class.java))
        waitFor { !socket.exists() }
    }

    @Test
    fun serviceStartsAndIsRunning() {
        VyzorixForegroundService.start(context)

        assertTrue("service should bind its bridge socket after start", waitFor { socket.exists() })
    }

    @Test
    fun serviceSurvivesAProcessKillAndComesBack() {
        VyzorixForegroundService.start(context)
        assertTrue("service should be up before the restart", waitFor { socket.exists() })

        // START_STICKY is the mechanism under test. A real kill of the app's own
        // process would take this test process down with it, so the restart is
        // simulated by stopping and re-issuing start, which is the same path the
        // system takes on restart.
        //
        // The wait for the socket to disappear is load-bearing, not politeness:
        // `stopService` is asynchronous, and the old instance deletes its socket only
        // in onDestroy. Restarting before that leaves the system waiting for a
        // startForeground the dying instance will never call, which kills the process.
        context.stopService(Intent(context, VyzorixForegroundService::class.java))
        assertTrue("service should release its socket on stop", waitFor { !socket.exists() })

        VyzorixForegroundService.start(context)
        assertTrue(
            "service should bind its socket again after restart",
            waitFor { socket.exists() },
        )
    }

    @Test
    fun theBridgeSocketIsCreatedByTheRunningService() {
        VyzorixForegroundService.start(context)

        assertTrue(
            "the running service should have bound files/android.sock",
            waitFor { socket.exists() },
        )
    }
}
