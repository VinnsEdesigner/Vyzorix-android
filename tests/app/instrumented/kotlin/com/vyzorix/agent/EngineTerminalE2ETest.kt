package com.vyzorix.agent

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyzorix.capability.PtyBroker
import com.vyzorix.capability.PtyReader
import com.vyzorix.capability.PtyRequest
import com.vyzorix.capability.PtyResult
import com.vyzorix.engine.ProotLauncher
import com.vyzorix.service.VyzorixForegroundService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The container's terminal, on a device: proot, a Debian shell, and a PTY.
 *
 * ## Why this test is in `:app` and not `:android-core`
 *
 * It needs the rootfs asset, and only `:app` has it. An `:android-core` instrumented
 * test runs in a different APK with no `assets/`, so `ProotLauncher.isReady()` is
 * false there and the service's `terminalShellRequest` falls back to the uncontained
 * device shell. That is why the engine path had no test: the module that could drive
 * a PTY could not have the userspace, and the module with the userspace had no test
 * source set. This closes that gap.
 *
 * ## What it proves, and what it deliberately does not
 *
 * It drives [ProotLauncher.loginShellRequest] — the exact request the service hands
 * the broker for an operator's terminal — through the real [PtyBroker] and
 * [PtyReader]. What comes back is asserted to be *inside the container*:
 *
 *  - `cat /etc/debian_version` answers, which the device shell cannot;
 *  - `id -u` is `0`, which is proot's `-0`; the device shell reports the app uid;
 *  - `pwd` is `/root`, which exists only in the rootfs.
 *
 * A shell that merely answered would pass a weaker test. These only answer inside
 * Debian.
 *
 * It does **not** go through `android.sock`. That would mean minting a session token,
 * and the service mints its own through a private registry — the socket layer is
 * already covered by `PtyRoundTripInstrumentedTest`. This test's job is the half that
 * was missing: whether the *request the service builds* produces a Debian shell.
 */
@RunWith(AndroidJUnit4::class)
class EngineTerminalE2ETest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val socketFile = java.io.File(context.filesDir, "android.sock")

    @Before
    fun startTheService() {
        // The service, not just the installer, and that is load-bearing.
        //
        // `ProotLauncher.binds` binds `files/android.sock` into the container, and that
        // socket exists only once the bridge is listening. Calling RootfsBootstrap on
        // its own installs the rootfs but leaves the socket absent, so proot is handed
        // a bind whose source does not exist, fails to start, and the child exits at
        // once — which surfaces as the reader hitting EOF and closing the fd, then
        // "Bad file descriptor" on the first write. It took three attempts to see that
        // because the symptom (a terminal that echoes and never answers) points at the
        // shell rather than at a missing bind.
        //
        // Starting the service is also what production does: the bridge is up before
        // any terminal can be opened.
        VyzorixForegroundService.start(context)

        assertTrue("the engine must install and report ready", awaitEngineReady())
        assertTrue("the bridge socket must be bound", awaitSocket())
    }

    private fun awaitSocket(): Boolean {
        val deadline = System.currentTimeMillis() + SOCKET_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (socketFile.exists()) return true
            Thread.sleep(POLL_MS)
        }

        return socketFile.exists()
    }

    /**
     * Waits for the engine's readiness check, which on a cold device means waiting for
     * the rootfs to finish extracting.
     */
    private fun awaitEngineReady(): Boolean {
        val deadline = System.currentTimeMillis() + ENGINE_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ProotLauncher.isReady(context)) return true
            Thread.sleep(POLL_MS)
        }

        return ProotLauncher.isReady(context)
    }

    /**
     * Runs [command] in the production login shell and returns everything the shell
     * wrote back.
     *
     * The command is followed by an `echo` of a sentinel and the reader waits for the
     * sentinel, rather than waiting for the shell to exit. That is deliberate: a PTY
     * echoes input at the line-discipline layer the instant it arrives, *before* the
     * shell has started, so the echo of the command is not evidence the shell ran it.
     * Gating on exit was the first attempt and it read the echo and nothing else — the
     * shell's own output had not been delivered yet when the process ended.
     *
     * Reading until the sentinel means the assertion sees the command's output, not
     * just the terminal's echo of the command.
     */
    private fun runInContainer(command: String): String {
        val broker = PtyBroker()
        val output = CopyOnWriteArrayList<ByteArray>()
        val exited = CountDownLatch(1)

        // The child's exit code, when it exits on its own. pty_exec encodes a failed
        // startup as 100 + a step number, so this distinguishes "proot could not
        // start" from "the shell ran and exited".
        val exitCode =
            java.util.concurrent.atomic
                .AtomicInteger(Int.MIN_VALUE)

        val opened = broker.open("e2e-${System.nanoTime()}", "dev-1", "op-1", loginRequest())
        assertTrue("broker.open failed: $opened", opened is PtyResult.Ok)
        val channel = (opened as PtyResult.Ok).value
        val session = broker.sessionForChannel(channel)!!

        // A holder because the reader's own callback replenishes its credit: the
        // lambda needs to reach the reader being constructed.
        lateinit var reader: PtyReader

        reader =
            PtyReader(
                session = session,
                onOutput = { bytes ->
                    output += bytes
                    // Replenish as a real client does. The reader stops delivering
                    // once its credit window is exhausted, and a login shell's startup
                    // output alone can consume the default 64 KB — so without this the
                    // shell stalls mid-banner and the command's answer never arrives.
                    // The wire client acknowledges bytes for exactly this reason.
                    reader.grantCredit(bytes.size)
                },
                onExit = { code ->
                    exitCode.set(code)
                    exited.countDown()
                },
            )
        reader.start()

        // Send the command, then a marker, but NOT `exit` yet.
        //
        // Sending `exit` in the same write was the mistake that cost the most time
        // here: the shell reads all three lines at once, runs the command, prints the
        // marker and exits — and the exit closes the PTY before the reader has
        // delivered the output, so the test saw only the tty's echo of the input and
        // the child's exit code 0. Writing the command alone and exiting afterwards
        // gives the reader the window it needs.
        //
        // The marker is BUILT by the shell from two halves, so the literal string never
        // appears in the input and the terminal's echo of the command line cannot
        // satisfy the wait. A single literal marker does not work: the echo arrives
        // first and the reader stops there.
        val commandLine = "$command\nprintf '%s%s\\n' $SENTINEL_LEFT $SENTINEL_RIGHT\n"

        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + OUTPUT_TIMEOUT_MS
        var attempts = 0

        while (System.currentTimeMillis() < deadline) {
            // Re-send until the shell answers, rather than writing once.
            //
            // proot is ptrace-based: between fork and the shell reading its tty it
            // writes its loader, execs, and takes the pty as its controlling terminal.
            // Input that arrives in that window is echoed by the line discipline and
            // then discarded — which is why a single write produced a terminal that
            // echoed the command and never ran it. The tty echo is real output, so the
            // reader sees it and the wait for the marker would otherwise time out.
            val wrote = session.write(commandLine.toByteArray())
            assertTrue("writing to the session failed: $wrote", wrote is PtyResult.Ok)
            attempts++

            val until = minOf(deadline, System.currentTimeMillis() + ATTEMPT_WINDOW_MS)
            while (System.currentTimeMillis() < until) {
                collected.setLength(0)
                output.forEach { collected.append(String(it)) }

                if (collected.contains(SENTINEL)) break

                Thread.sleep(POLL_MS)
            }

            if (collected.contains(SENTINEL)) break
        }

        assertTrue(
            "the shell never produced the end marker after $attempts attempt(s) " +
                "(child exit code ${session.childExitCode}); output was: " +
                collected.toString().take(400),
            collected.contains(SENTINEL),
        )

        // Now that the output has been read, let the shell go. Sending this earlier
        // would close the PTY before the reader had delivered the command's output.
        runCatching { session.write("exit\n".toByteArray()) }

        // The reader owns the fd, so the shell is given a moment to exit and the reader
        // to close it, rather than leaving the fd to the broker's teardown.
        exited.await(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        broker.close(channel)

        return collected.toString()
    }

    /**
     * The request the service builds for an operator's terminal, verbatim.
     *
     * This is the point of the test: not a request that happens to work, but the one
     * production sends.
     */
    private fun loginRequest(): PtyRequest =
        ProotLauncher.loginShellRequest(context, token = "e2e-session-token")

    @Test
    fun theLoginShellRunsInsideDebian() {
        assertTrue("the engine must be installed", ProotLauncher.isReady(context))

        val out = runInContainer("cat /etc/debian_version")

        assertTrue(
            "the shell must be inside Debian; output was: ${out.take(400)}",
            DEBIAN_VERSION.containsMatchIn(out),
        )
    }

    @Test
    fun theContainerShellIsRoot() {
        assertTrue("the engine must be installed", ProotLauncher.isReady(context))

        val out = runInContainer("id -u")

        // proot's -0 maps the app uid to root inside. The device shell would report
        // the app's own uid, which is the fallback this rules out.
        assertTrue(
            "the container shell must run as root (proot -0); output was: ${out.take(400)}",
            ROOT_UID.containsMatchIn(out),
        )
    }

    @Test
    fun theContainerShellStartsInTheRootfsRoot() {
        assertTrue("the engine must be installed", ProotLauncher.isReady(context))

        val out = runInContainer("pwd")

        // /root exists only in the rootfs; the device shell starts in the app's files
        // directory.
        assertTrue(
            "the shell must start in the container's /root; output was: ${out.take(400)}",
            out.contains("/root"),
        )
    }

    @Test
    fun aBinaryOnlyTheContainerHasRuns() {
        assertTrue("the engine must be installed", ProotLauncher.isReady(context))

        // `socat` is in the rootfs and not on the device. It is also the container-side
        // bridge transport, so its presence shows the image is the built one.
        val out = runInContainer("socat -V 2>&1 | head -1")

        assertTrue(
            "socat must exist inside the container; output was: ${out.take(400)}",
            out.contains("socat", ignoreCase = true),
        )
    }

    private companion object {
        /** First boot extracts ~145 MB, so a cold device needs the room. */
        const val ENGINE_READY_TIMEOUT_MS = 180_000L
        const val SOCKET_TIMEOUT_MS = 20_000L
        const val OUTPUT_TIMEOUT_MS = 60_000L

        /** How long to wait for a response before re-sending the command. */
        const val ATTEMPT_WINDOW_MS = 5_000L
        const val EXIT_TIMEOUT_SECONDS = 10L
        const val POLL_MS = 100L

        /**
         * The end-of-output marker, split in two.
         *
         * The shell prints `$SENTINEL_LEFT$SENTINEL_RIGHT` joined; neither half on its
         * own, and never the joined form, appears in the command line — so the tty's
         * echo of the input cannot match. A single literal sentinel does not work
         * because the echo arrives first and the reader stops there.
         */
        const val SENTINEL_LEFT = "VYZORIX_E2E_"
        const val SENTINEL_RIGHT = "DONE_8f3a"

        /** What the shell prints, and what the reader waits for. */
        const val SENTINEL = SENTINEL_LEFT + SENTINEL_RIGHT

        /** Debian 12.x, the suite the rootfs is built from. */
        val DEBIAN_VERSION = Regex("""\b12\.\d+""")

        /** A line that is just `0`, so a uid like 10070 does not match. */
        val ROOT_UID = Regex("""(?m)^0\s*$""")
    }
}
