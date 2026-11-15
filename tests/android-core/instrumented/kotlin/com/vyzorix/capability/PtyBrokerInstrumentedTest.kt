package com.vyzorix.capability

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real syscall paths on a device.
 *
 * These cannot run on the JVM: they need a kernel that can allocate a PTY and
 * fork. They are the tests that answer whether the capability layer actually
 * works, as opposed to whether it compiles.
 *
 * The broker hands back a channel, not the session, so the tests resolve the
 * session through [PtyBroker.sessionForChannel] the way the service does.
 */
@RunWith(AndroidJUnit4::class)
class PtyBrokerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun readUntil(
        session: PtySession,
        needle: String,
        timeoutMs: Long = 4000,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val collected = StringBuilder()

        while (System.currentTimeMillis() < deadline) {
            when (val outcome = session.read()) {
                is PtyResult.Ok ->
                    when (val value = outcome.value) {
                        is PtyReadOutcome.Data -> {
                            collected.append(String(value.bytes))
                            if (collected.contains(needle)) return collected.toString()
                        }
                        PtyReadOutcome.Eof -> return collected.toString()
                        PtyReadOutcome.Timeout -> Unit
                    }
                is PtyResult.Err -> error("read failed: ${outcome.error}")
            }
        }

        return collected.toString()
    }

    @Test
    fun opensPtyAndRunsACommand() {
        val broker = PtyBroker()

        val opened =
            broker.open(
                sessionId = "test-1",
                deviceId = "dev-1",
                userId = "op-1",
                request = PtyRequest(executable = "/system/bin/sh"),
            )

        assertTrue("open failed: $opened", opened is PtyResult.Ok)
        val channel = (opened as PtyResult.Ok).value
        val session = broker.sessionForChannel(channel)!!

        try {
            assertTrue(session.masterFd >= 0)
            assertTrue(session.childPid > 0)

            val wrote = session.write("echo BROKER_MARKER_OK\n".toByteArray())
            assertTrue("write failed: $wrote", wrote is PtyResult.Ok)

            val output = readUntil(session, "BROKER_MARKER_OK")
            assertTrue("marker not seen in output: $output", output.contains("BROKER_MARKER_OK"))
        } finally {
            broker.close(channel)
            session.releaseFd()
        }
    }

    @Test
    fun executesFromAppStorage() {
        // The load-bearing case: exec a file in the app's own data dir. This is
        // what targetSdk 28 buys, and it is why the pin exists.
        val script = File(context.filesDir, "broker_probe.sh")
        script.writeText("#!/system/bin/sh\necho APP_STORAGE_MARKER\n")
        script.setExecutable(true, false)

        val broker = PtyBroker()
        val opened =
            broker.open(
                sessionId = "test-2",
                deviceId = "dev-1",
                userId = "op-1",
                request = PtyRequest(executable = script.absolutePath),
            )

        assertTrue("open failed: $opened", opened is PtyResult.Ok)
        val channel = (opened as PtyResult.Ok).value
        val session = broker.sessionForChannel(channel)!!

        try {
            val output = readUntil(session, "APP_STORAGE_MARKER")
            assertTrue(
                "exec from app storage did not produce the marker: $output",
                output.contains("APP_STORAGE_MARKER"),
            )
        } finally {
            broker.close(channel)
            session.releaseFd()
        }
    }

    @Test
    fun resizeAppliesToTheSession() {
        val broker = PtyBroker()
        val opened =
            broker.open(
                sessionId = "test-3",
                deviceId = "dev-1",
                userId = "op-1",
                request = PtyRequest(executable = "/system/bin/sh", rows = 24, cols = 80),
            )

        val channel = (opened as PtyResult.Ok).value
        val session = broker.sessionForChannel(channel)!!
        try {
            assertTrue(session.resize(50, 120) is PtyResult.Ok)
        } finally {
            broker.close(channel)
            session.releaseFd()
        }
    }

    @Test
    fun duplicateSessionIdIsRejected() {
        val broker = PtyBroker()
        val first = broker.open("dup", "dev-1", "op-1", PtyRequest("/system/bin/sh"))
        val channel = (first as PtyResult.Ok).value
        val session = broker.sessionForChannel(channel)!!

        try {
            val second = broker.open("dup", "dev-1", "op-1", PtyRequest("/system/bin/sh"))
            assertTrue(second is PtyResult.Err)
            assertEquals(PtyError.Kind.SESSION_EXISTS, (second as PtyResult.Err).error.kind)
        } finally {
            broker.close(channel)
            session.releaseFd()
        }
    }

    @Test
    fun badExecutableReportsExecFailed() {
        val broker = PtyBroker()
        val opened =
            broker.open(
                sessionId = "test-4",
                deviceId = "dev-1",
                userId = "op-1",
                request = PtyRequest(executable = "/nonexistent/binary"),
            )

        // The fork succeeds, so open succeeds; the failure surfaces as the child
        // exiting rather than as an open error.
        assertTrue(opened is PtyResult.Ok)
        val channel = (opened as PtyResult.Ok).value
        val session = broker.sessionForChannel(channel)!!

        try {
            var reaped = false
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline && !reaped) {
                reaped = (session.reapIfExited() as? PtyResult.Ok)?.value ?: false
                if (!reaped) Thread.sleep(50)
            }

            assertTrue("child with a bad exec path should have exited", reaped)
            assertTrue(
                "expected a non-zero exit, got ${session.childExitCode}",
                session.childExitCode != 0,
            )
        } finally {
            broker.close(channel)
            session.releaseFd()
        }
    }

    @Test
    fun fdPassingRoundTrips() {
        // The SCM_RIGHTS code is exercised natively: it creates a socketpair,
        // sends a real descriptor, receives it, and writes through the copy.
        // Testing it in Kotlin would mostly test Kotlin's FileDescriptor API.
        val result = NativePty.selfTestFdPassing()

        assertTrue("native fd-passing self test failed: ${result.detail}", result.isOk)
        assertTrue(
            "expected a successful round trip, got: ${result.detail}",
            result.detail.contains("roundtrip=ok"),
        )
    }

    @Test
    fun sessionCapIsEnforcedOnDevice() {
        val broker = PtyBroker()
        val opened = mutableListOf<Pair<Int, PtySession>>()

        try {
            repeat(PtySessionRegistry.DEFAULT_MAX_SESSIONS_PER_DEVICE) { i ->
                val result = broker.open("cap-$i", "capdev", "op-1", PtyRequest("/system/bin/sh"))
                assertTrue("open $i failed: $result", result is PtyResult.Ok)
                val channel = (result as PtyResult.Ok).value
                opened += channel to broker.sessionForChannel(channel)!!
            }

            val overflow = broker.open("cap-over", "capdev", "op-1", PtyRequest("/system/bin/sh"))
            assertEquals(
                PtyError.Kind.TOO_MANY_SESSIONS,
                (overflow as PtyResult.Err).error.kind,
            )
        } finally {
            opened.forEach { (channel, session) ->
                broker.close(channel)
                session.releaseFd()
            }
        }
    }

    @Test
    fun closingASessionLeavesTheFdCountUnchanged() {
        // Definition-of-done item 5: opening and closing 50 PTY sessions must leave
        // the descriptor count unchanged. The reader owns the fd and closes it;
        // this proves the Build 1 leak is fixed.
        val broker = PtyBroker()
        val before = openFdCount()

        repeat(50) { i ->
            val opened = broker.open("leak-$i", "dev-leak", "op-1", PtyRequest("/system/bin/sh"))
            assertTrue(opened is PtyResult.Ok)
            val channel = (opened as PtyResult.Ok).value
            val session = broker.sessionForChannel(channel)!!
            broker.close(channel)
            session.releaseFd()
        }

        val after = openFdCount()
        assertTrue("fd count grew from $before to $after over 50 sessions", after <= before + 1)
    }

    private fun openFdCount(): Int = File("/proc/self/fd").list()?.size ?: -1
}
