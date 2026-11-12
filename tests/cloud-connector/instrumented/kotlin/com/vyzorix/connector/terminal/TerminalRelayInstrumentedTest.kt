package com.vyzorix.connector.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vyzorix.bridge.SessionTokenRegistry
import com.vyzorix.capability.PtyBroker
import com.vyzorix.capability.PtyRequest
import com.vyzorix.connector.cloud.PtyFrame
import com.vyzorix.service.ServicePtySessionPort
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cloud terminal end to end on a device: a real PTY, a real shell, driven
 * through the relay with the same frames the server sends.
 *
 * The unit tests cover routing with a fake port; this covers the half they cannot —
 * that a shell actually starts, produces output, receives keystrokes, resizes and
 * reaps, through the real [PtyBroker] and [PtyReader]. It uses `/system/bin/sh`
 * rather than the proot login shell, because the Debian rootfs is not in the repo;
 * the PTY path is identical, which is what this proves.
 *
 * Instrumented, so it runs on the emulator or a device, never the JVM: it touches
 * `posix_openpt` and `fork`.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRelayInstrumentedTest {
    /** A port wired to a real broker, a plain shell, and the relay's callbacks. */
    private class Harness {
        val broker = PtyBroker()
        val output = CopyOnWriteArrayList<String>()
        val exited = CountDownLatch(1)
        val closed = CopyOnWriteArrayList<Pair<String, String>>()
        val frames = CopyOnWriteArrayList<ByteArray>()

        val port =
            ServicePtySessionPort(
                broker = broker,
                tokens = SessionTokenRegistry(),
                deviceId = { "test-device" },
                unattributedAllowlist = { setOf("identity") },
                // The session id is ignored here: production names the shell's tmux
                // session after it so a reconnect reattaches, but this test drives a
                // plain device shell (the rootfs is not in this APK), so there is
                // nothing to name.
                shellRequest = { _, _, _ ->
                    PtyRequest(
                        executable = "/system/bin/sh",
                        arguments = listOf("/system/bin/sh"),
                        environment = listOf("TERM=dumb"),
                    )
                },
            )

        val relay =
            TerminalRelay(
                port = port,
                sendFrame = { frame ->
                    frames += frame
                    true
                },
                onSessionClosed = { id, reason -> closed += id to reason },
            )

        /** The decoded frames sent to the server so far. */
        fun sent(): List<PtyFrame.Frame> = frames.map { PtyFrame.decode(it) }

        /** All DATA payloads for a session, concatenated. */
        fun outputFor(sessionId: String): String =
            sent()
                .filter { it.type == PtyFrame.TYPE_DATA && it.sessionId == sessionId }
                .joinToString("") { String(it.payload) }
    }

    @Test
    fun aShellStartsAndItsOutputReachesTheServer() {
        val h = Harness()

        // OPEN, exactly as the operator path sends it: session id + initial size.
        val opened =
            h.relay.onFrame(
                PtyFrame.encode(PtyFrame.TYPE_OPEN, PtyFrame.resizePayload(24, 80), "s1"),
            )
        assertTrue("the open frame must be handled", opened)
        assertTrue(h.relay.isOpen("s1"))

        // A command, as keystrokes.
        h.relay.onFrame(PtyFrame.encodeData("s1", "echo relay-works\n".toByteArray()))

        // Wait for the shell's output to come back as DATA frames.
        val deadline = System.currentTimeMillis() + 15_000
        var seen = ""
        while (System.currentTimeMillis() < deadline && !seen.contains("relay-works")) {
            Thread.sleep(200)
            seen = h.outputFor("s1")
        }

        assertTrue(
            "the shell's output must reach the server; got: $seen",
            seen.contains("relay-works"),
        )

        h.relay.closeSession("s1", "test_done")
        assertTrue("the session must be reaped", h.closed.any { it.first == "s1" })
    }

    @Test
    fun aResizeReachesTheShell() {
        val h = Harness()
        h.relay.onFrame(PtyFrame.encode(PtyFrame.TYPE_OPEN, PtyFrame.resizePayload(24, 80), "s2"))

        assertTrue(h.relay.onFrame(PtyFrame.encodeResize("s2", rows = 40, cols = 120)))

        // `stty size` reports the pty's window; if the resize reached the kernel this
        // prints "40 120".
        h.relay.onFrame(PtyFrame.encodeData("s2", "stty size\n".toByteArray()))

        val deadline = System.currentTimeMillis() + 15_000
        var seen = ""
        while (System.currentTimeMillis() < deadline && !seen.contains("40 120")) {
            Thread.sleep(200)
            seen = h.outputFor("s2")
        }

        assertTrue("the resize must reach the pty; got: $seen", seen.contains("40 120"))

        h.relay.closeSession("s2", "test_done")
    }

    @Test
    fun anExitingShellClosesTheSession() {
        val h = Harness()
        h.relay.onFrame(PtyFrame.encode(PtyFrame.TYPE_OPEN, PtyFrame.resizePayload(24, 80), "s3"))

        // Exit the shell; the reader's exit callback must close the session and tell
        // the server, so the operator's terminal ends instead of hanging.
        h.relay.onFrame(PtyFrame.encodeData("s3", "exit\n".toByteArray()))

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && h.relay.isOpen("s3")) {
            Thread.sleep(200)
        }

        assertTrue("an exited shell must close the session", !h.relay.isOpen("s3"))
        assertTrue(
            "the server must be told the shell exited",
            h.closed.any { it.first == "s3" && it.second == "shell_exited" },
        )
        assertEquals(1, h.closed.count { it.first == "s3" })
    }
}
