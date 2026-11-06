package com.vyzorix.agent

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyzorix.engine.ProotLauncher
import com.vyzorix.service.VyzorixForegroundService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every bridge API, called the way an operator calls it: **from inside the
 * container, through the real `android-bin`, over the real socket**.
 *
 * ## Why this test exists, and what the JVM test does not cover
 *
 * `ApiSmokeTest` calls each handler through the router on Robolectric. That catches
 * a handler that throws, but it does not exercise the path that actually matters:
 *
 *     proot -> android-bin -> /tmp/android.sock -> the bridge -> the router
 *
 * Every one of those steps can fail on a device while Robolectric is perfectly
 * happy. The socket is bound by a *native* call, the client is a C++ binary running
 * under proot, the token arrives in the environment, and the container's view of
 * `/tmp/android.sock` comes from a bind. Robolectric has none of that, and it
 * supplies system services a real device may not.
 *
 * So this runs `android-bin <api>` in the container's shell and asserts the result
 * comes back as JSON. A failure here is a failure an operator would have.
 *
 * ## What it asserts, and why that shape
 *
 * The assertion is on the **transport and the shape**, not on the values: most of
 * these read real device state, and the emulator's answer is not this test's
 * business. What it proves is that the command exists, the token was accepted, the
 * router dispatched it, the handler answered, and the reply came back parseable.
 *
 * An `unknown_api` would mean `apis.txt` and the router disagree — the exact drift
 * `ApiCatalogTest` exists to catch, now checked on a device as well. A `forbidden`
 * means a permission the container cannot have, which is reported rather than
 * failed, because a dangerous permission is expected to be absent on a fresh
 * install.
 */
@RunWith(AndroidJUnit4::class)
class ApiLiveE2ETest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun startTheService() {
        VyzorixForegroundService.start(context)

        assertTrue(
            "the engine must install and report ready",
            await(READY_TIMEOUT_MS) { ProotLauncher.isReady(context) },
        )
        assertTrue(
            "the bridge socket must be bound",
            await(READY_TIMEOUT_MS) { socketFile().exists() },
        )
        // **The supervisor is the readiness gate for the container's tree, and that
        // is not a shortcut.**
        //
        // `hasSupervisor()` becomes true only after the rootfs, the extended tier and
        // the container tools have all been written — the service installs them in
        // that order and spawns the supervisor last, precisely so this signal means
        // "the tree is complete". The bridge socket, by contrast, is up *before* any
        // of it: waiting on the socket alone raced the install, and the first command
        // failed with `android-bin: command not found` — which reads as a broken API
        // rather than as a test that started too early.
        assertTrue(
            "the container tools must be installed",
            await(READY_TIMEOUT_MS) { ProotLauncher.hasSupervisor(context) },
        )
        // **And the port itself, because the signals above are filesystem state.**
        //
        // `isReady` and `hasSupervisor` are satisfied the instant the files exist —
        // including files left by a previous run on the same device. The service,
        // however, is started asynchronously (`startForegroundService` posts to the
        // main thread) and only publishes its port at the end of `start()`. So on a
        // device that already has the tree, the two waits above return immediately
        // while the service has not run yet, and the first `runInContainer` finds no
        // port. Waiting on the port is waiting on the thing the test actually uses.
        assertTrue(
            "the service's terminal port must be published",
            await(READY_TIMEOUT_MS) { VyzorixForegroundService.terminalPortOrNull() != null },
        )
    }

    private fun socketFile() = java.io.File(context.filesDir, "android.sock")

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

    /**
     * Runs one command in a real container shell and returns everything it wrote.
     *
     * The shell is opened through the **service's own terminal port**, not built here.
     * That is the whole point: the port mints the session token from the service's
     * registry, and a token minted anywhere else is one the bridge has never seen —
     * every request is then refused, which looks like a broken API rather than a
     * test that took a shortcut.
     *
     * ## Why a fresh session is retried
     *
     * Starting a proot session is occasionally *flaky* on the emulator: the child
     * dies during startup and the shell never reads its tty, so only the kernel's echo
     * of the input comes back and the marker never appears. This is a known,
     * pre-existing race (proot's SIGSYS translation racing the seccomp trap; the same
     * one that makes `ProotFromAppProcessTest` flaky), not an API failure — the
     * identical command succeeds on the next shell. A whole-sweep test cannot afford
     * to fail on it, so a shell that produces nothing is discarded and the command is
     * retried on a fresh one. A command that fails on *every* fresh shell is a real
     * failure and is reported.
     */
    private fun runInContainer(command: String): String {
        repeat(SHELL_ATTEMPTS) { attempt ->
            val result = runOnceInFreshShell(command)
            if (result != null) return result

            println("$command produced nothing on shell ${attempt + 1}/$SHELL_ATTEMPTS; retrying")
        }

        error("$command produced no output after $SHELL_ATTEMPTS fresh shells")
    }

    /**
     * One attempt: open a shell, run [command], wait for the end marker.
     *
     * Returns the shell's output, or null when the shell never produced the marker
     * (a startup failure the caller retries).
     */
    private fun runOnceInFreshShell(command: String): String? {
        val port =
            VyzorixForegroundService.terminalPortOrNull()
                ?: error("the service's terminal port is not up")

        val output = CopyOnWriteArrayList<ByteArray>()
        val exited = CountDownLatch(1)
        val sessionId = "api-${System.nanoTime()}"

        val opened =
            port.open(
                sessionId = sessionId,
                rows = 24,
                cols = 80,
                onOutput = { _, bytes -> output += bytes },
                onExit = { _, _ -> exited.countDown() },
            )
        assertTrue("the terminal port must open a shell", opened)

        // The command, then a marker the shell builds from two halves, so the tty's
        // echo of the input cannot satisfy the wait.
        val line = "$command\nprintf '%s%s\\n' $SENTINEL_LEFT $SENTINEL_RIGHT\n"
        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + OUTPUT_TIMEOUT_MS

        // Re-send until the shell answers: proot writes its loader and execs between
        // the fork and the shell reading its tty, and input arriving in that window is
        // echoed by the line discipline and then discarded.
        while (System.currentTimeMillis() < deadline) {
            port.write(sessionId, line.toByteArray())

            val until = minOf(deadline, System.currentTimeMillis() + ATTEMPT_WINDOW_MS)
            while (System.currentTimeMillis() < until) {
                collected.setLength(0)
                output.forEach { collected.append(String(it)) }
                if (collected.contains(SENTINEL)) break
                Thread.sleep(POLL_MS)
            }

            if (collected.contains(SENTINEL)) break
        }

        port.close(sessionId)
        exited.await(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        return if (collected.contains(SENTINEL)) collected.toString() else null
    }

    /**
     * Extracts the last JSON object in `text`.
     *
     * The output contains the echo of the command and any shell noise; the answer is
     * the last `{...}` before the marker, so the tail is scanned backwards for a
     * parseable object rather than assuming the first `{` is it.
     *
     * `android-bin` prints the handler's result **directly** — `BridgeClient::Call`
     * unwraps the bridge's `{"ok":true,"result":"…"}` envelope and `main` prints the
     * inner string. So a successful call's stdout is the result object itself, with
     * no wrapper, and that is what is parsed here.
     */
    private fun lastJson(text: String): JSONObject? {
        val end = text.indexOf(SENTINEL)
        val body = if (end >= 0) text.substring(0, end) else text

        var start = body.length - 1
        while (start >= 0) {
            val open = body.lastIndexOf('{', start)
            if (open < 0) return null

            val candidate = body.substring(open).trim()
            val parsed = runCatching { JSONObject(candidate) }.getOrNull()
            if (parsed != null) return parsed

            start = open - 1
        }

        return null
    }

    @Test
    fun everyApiAnswersThroughTheContainer() {
        val failures = mutableListOf<String>()
        val checked = mutableListOf<String>()

        // `session` is excluded: it opens a PTY, and calling it from inside a PTY is a
        // recursion this test has no business performing.
        val apis =
            com.vyzorix.bridge.ApiCatalogue.names
                .filter { it != "session" }

        for (api in apis) {
            // The API name *is* the command: `battery`, `device_info` and the rest are
            // symlinks to `android-bin`, and the basename is what selects the API. This
            // is the form an operator types, so it is the form the test must use.
            val output = runInContainer(api)
            val json = lastJson(output)

            // A permission refusal is *not* JSON on stdout: `android-bin` turns the
            // bridge's error into `"<api>: forbidden: <detail>"` on stderr, and the PTY
            // merges the streams. So a refusal is matched as text, not parsed. It is an
            // expected outcome on a fresh install — a dangerous permission is genuinely
            // absent — and is recorded rather than failed.
            if (output.contains("forbidden:")) {
                checked += "$api (refused: permission)"
                continue
            }

            // Any *other* structured error is also an answer, not a failure. The
            // contract this test asserts is the transport: the command ran, the token
            // was accepted, the router dispatched, the handler produced a structured
            // outcome. A handler that reports `internal: /proc/loadavg is not readable`
            // has proven all of that — the value is simply unavailable on this device
            // (the emulator denies `/proc/loadavg` even to the adb shell). The one kind
            // that is *not* an answer is `unknown_api`, which means the command and the
            // router disagree; that is checked next and failed.
            val structuredError =
                Regex("""^$api: ([a-z_]+): """, RegexOption.MULTILINE)
                    .find(output)
                    ?.groupValues
                    ?.get(1)
            if (structuredError != null && structuredError != "unknown_api") {
                checked += "$api (error: $structuredError)"
                continue
            }

            // `unknown_api` would mean `apis.txt` and the router disagree — the exact
            // drift `ApiCatalogTest` exists to catch, now checked on a device as well.
            // It is a hard failure, not an expected refusal.
            if (output.contains("unknown_api") || output.contains("unknown api")) {
                failures += "$api -> unknown API through the container: ${output.take(200)}"
                continue
            }

            if (json == null) {
                failures += "$api -> no JSON in output: ${output.take(200)}"
                continue
            }

            checked += api
        }

        assertTrue(
            "these APIs failed through the container:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
        assertTrue(
            "every API should have answered or been refused for a permission; got " +
                "${checked.size} of ${apis.size}",
            checked.size == apis.size,
        )
    }

    @Test
    fun aSpecificApiReturnsTheExpectedShape() {
        // One API asserted on its *values*, so this is not only a transport test.
        // `device_info` needs no permission and its fields are fixed by the platform,
        // so it is the one that can be checked for content rather than shape.
        val output = runInContainer("device_info")
        val json = lastJson(output)

        assertTrue(
            "device_info must answer with JSON; output was: ${output.take(300)}",
            json != null,
        )

        // No `result` wrapper: the handler's JSON is the whole payload (see lastJson).
        assertTrue("the result must carry the model", json!!.has("model"))
        assertTrue("the result must carry the API level", json.has("apiLevel"))
        assertTrue(
            "the API level must be the device's, not a default",
            json.optInt("apiLevel") > 0,
        )
    }

    @Test
    fun anUnknownApiIsReportedAsSuch() {
        // The other half: a name the router does not serve must say so, rather than
        // hang or answer with something plausible. `android-bin` refuses an unknown
        // name client-side before connecting, so this checks the client's own guard.
        val output = runInContainer("android-bin --api definitely_not_an_api")

        assertTrue(
            "an unknown API must be refused; output was: ${output.take(300)}",
            output.contains("unknown api") || output.contains("unknown_api"),
        )
    }

    private companion object {
        const val READY_TIMEOUT_MS = 180_000L
        const val OUTPUT_TIMEOUT_MS = 30_000L
        const val EXIT_TIMEOUT_SECONDS = 5L
        const val POLL_MS = 100L

        /**
         * How many fresh shells a single command may be tried on.
         *
         * proot's startup is occasionally flaky on the emulator (see [runInContainer]),
         * and the sweep runs ~57 shells in a row, so one unlucky shell must not fail
         * the run. Three is enough to clear the race without hiding a command that
         * genuinely never works.
         */
        const val SHELL_ATTEMPTS = 3

        /** How long to wait for an answer before re-sending the command. */
        const val ATTEMPT_WINDOW_MS = 4_000L

        const val SENTINEL_LEFT = "VYZORIX_API_"
        const val SENTINEL_RIGHT = "DONE_91c7"
        const val SENTINEL = SENTINEL_LEFT + SENTINEL_RIGHT
    }
}
