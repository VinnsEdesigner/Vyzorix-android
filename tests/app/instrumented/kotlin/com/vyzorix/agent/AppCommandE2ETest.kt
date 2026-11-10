package com.vyzorix.agent

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyzorix.connector.command.AppCommands
import com.vyzorix.connector.command.AppControlClient
import com.vyzorix.engine.ProotLauncher
import com.vyzorix.service.VyzorixForegroundService
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cloud command path into the Layer 3 supervisor, against the **real** supervisor
 * running in the container.
 *
 * ## Why this is in `:app`
 *
 * It needs the rootfs asset and the container binaries, and only `:app` has them. A
 * `:cloud-connector` test would have to fake the supervisor's socket — and a fake
 * proves the fake, not the contract. This drives the same `AppCommands.Start`
 * executor the cloud dispatcher uses, over the same `AppControlClient`, at the same
 * `files/apps/supervisor.sock` the service installs. What it proves is that the whole
 * chain works: executor -> control socket -> C++ supervisor -> a running process.
 *
 * ## What it asserts, and why those things
 *
 * The app is started and then *observed*: the registry must record a pid and a
 * start-time token, which only the supervisor writes, and `app-list` must report it.
 * A command that merely returned "ok" would prove nothing — the supervisor answers
 * `ok` for a start it could not perform just as readily as for one it did, and the
 * pid record is the difference.
 */
@RunWith(AndroidJUnit4::class)
class AppCommandE2ETest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The host path of the container's `/home/apps/supervisor.sock`. */
    private val socket = File(context.filesDir, "${ProotLauncher.APPS_DIR}/supervisor.sock")

    private lateinit var client: AppControlClient

    @Before
    fun startTheService() {
        VyzorixForegroundService.start(context)

        client = AppControlClient(socket)

        assertTrue("the engine must install and report ready", awaitEngineReady())
        assertTrue("the container binaries must be installed", awaitSupervisor())
        assertTrue("the supervisor must answer on its control socket", awaitSocket())
    }

    private fun awaitEngineReady(): Boolean =
        await(READY_TIMEOUT_MS) {
            ProotLauncher.isReady(context)
        }

    private fun awaitSupervisor(): Boolean =
        await(READY_TIMEOUT_MS) {
            ProotLauncher.hasSupervisor(context)
        }

    /**
     * Waits until the supervisor actually *answers*, not merely until the socket
     * exists.
     *
     * The file appears when the supervisor binds, but a connect can still be refused
     * for a moment afterwards — and on a cold boot the rootfs extraction and the
     * spawn are racing. `exists()` alone made the first test of a run fail with
     * "Connection refused" while the rest passed, which is a readiness check that
     * checks the wrong thing.
     */
    private fun awaitSocket(): Boolean =
        await(READY_TIMEOUT_MS) {
            if (!socket.exists()) return@await false

            client.call("ping").success
        }

    private fun await(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(POLL_MS)
        }

        return predicate()
    }

    /** Creates `name` with a command the core rootfs certainly has. */
    private fun createSleeper(name: String) {
        val created =
            client.call(
                "create",
                mapOf(
                    "name" to name,
                    "cmd" to "/bin/sleep 600",
                    "dir" to "/home/apps/$name",
                    "port" to "0",
                    "autostart" to "false",
                ),
            )
        assertTrue("create must succeed: ${created.output}", created.success)
    }

    /** The registry as text, through the same socket the CLI uses. */
    private fun listApps(): String {
        val listed = client.call("list")
        assertTrue("list must succeed: ${listed.output}", listed.success)

        return listed.output
    }

    @Test
    fun theStartCommandStartsAnAppInTheContainer() {
        val name = "cmd-start-${System.nanoTime()}"
        createSleeper(name)

        val started = AppCommands.Start(client).execute("{\"app\":\"$name\"}")
        assertTrue("APP_START must succeed: ${started.output}", started.success)

        // The supervisor writes the pid and the start-time token; the command plane
        // never does. Seeing them is what distinguishes "the executor returned ok"
        // from "the app is actually running".
        val deadline = System.currentTimeMillis() + RUNNING_TIMEOUT_MS
        var registry = listApps()
        while (System.currentTimeMillis() < deadline && !registry.contains("\"pid\":0")) {
            if (registryHasLivePid(registry, name)) break
            Thread.sleep(POLL_MS)
            registry = listApps()
        }

        assertTrue(
            "the registry must record a running pid for $name; registry was: " +
                registry.take(400),
            registryHasLivePid(registry, name),
        )
    }

    @Test
    fun theStopCommandStopsTheAppAndClearsItsRecord() {
        val name = "cmd-stop-${System.nanoTime()}"
        createSleeper(name)
        val started = AppCommands.Start(client).execute("{\"app\":\"$name\"}")
        assertTrue("start must succeed: ${started.output}", started.success)

        val stopped = AppCommands.Stop(client).execute("{\"app\":\"$name\"}")
        assertTrue("APP_STOP must succeed: ${stopped.output}", stopped.success)

        // The record is cleared, which is how the supervisor marks "not running".
        val deadline = System.currentTimeMillis() + RUNNING_TIMEOUT_MS
        var cleared = false
        while (System.currentTimeMillis() < deadline && !cleared) {
            cleared = !registryHasLivePid(listApps(), name)
            if (!cleared) Thread.sleep(POLL_MS)
        }

        assertTrue("the pid record must be cleared after a stop", cleared)
    }

    @Test
    fun anUnknownAppIsReportedAsAFailure() {
        // A command that could not be carried out must be
        // reported failed, so the operator learns it did not run.
        val outcome = AppCommands.Start(client).execute("{\"app\":\"definitely-not-an-app\"}")

        assertTrue("an unknown app must not report success", !outcome.success)
        assertTrue(
            "the failure should name the app: ${outcome.output}",
            outcome.output.contains("definitely-not-an-app"),
        )
    }

    @Test
    fun aCommandWithNoAppNameIsReportedAsAFailure() {
        val outcome = AppCommands.Start(client).execute("{}")

        assertTrue("a missing name must not report success", !outcome.success)
        assertTrue(
            "the failure should say what is needed: ${outcome.output}",
            outcome.output.contains("app name"),
        )
    }

    /**
     * True when the registry has a non-zero pid and a start-time token for `name`.
     *
     * Parsed rather than substring-matched: a name that is a prefix of another app's
     * would match the wrong entry, and the pid must be non-zero *and* paired with a
     * token — a pid alone is the thing that must never be trusted.
     */
    private fun registryHasLivePid(
        registry: String,
        name: String,
    ): Boolean {
        val parsed =
            runCatching { org.json.JSONObject(registry) }.getOrNull() ?: return false
        val apps = parsed.optJSONArray("apps") ?: return false

        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            if (app.optString("name") != name) continue

            return app.optInt("pid") > 0 && app.optLong("pidStartTicks") > 0
        }

        return false
    }

    private companion object {
        const val READY_TIMEOUT_MS = 180_000L
        const val RUNNING_TIMEOUT_MS = 20_000L
        const val POLL_MS = 200L
    }
}
