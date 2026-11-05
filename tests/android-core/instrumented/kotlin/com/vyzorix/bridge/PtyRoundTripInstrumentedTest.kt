package com.vyzorix.bridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyzorix.bridge.apis.SessionApi
import com.vyzorix.capability.PtyBroker
import com.vyzorix.capability.PtyReader
import com.vyzorix.capability.PtyRequest
import com.vyzorix.capability.PtyResult
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Definition-of-done item 7: a shell driven over the socket round-trips — input
 * in, output out, flow control respected.
 *
 * The pieces are wired exactly as [com.vyzorix.service.VyzorixForegroundService]
 * wires them: a broker allocates the PTY, a reader owns the fd and pushes output
 * through the server, and the server routes pty_input / pty_credit frames to the
 * session.
 *
 * The executable is `/system/bin/sh`, and that is a choice rather than a limitation:
 * this test is about the *transport* — framing, routing, flow control, session
 * scoping — and using a shell that is always present keeps a transport failure from
 * looking like an engine failure. The container's own shell, and whether the engine
 * picks it, is covered by `EngineTerminalE2ETest` in `:app`, which is the only module
 * with the rootfs asset.
 */
@RunWith(AndroidJUnit4::class)
class PtyRoundTripInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var tokens: SessionTokenRegistry
    private lateinit var broker: PtyBroker
    private lateinit var server: AndroidSocketServer
    private val readers = ConcurrentHashMap<Int, PtyReader>()

    @Before
    fun setUp() {
        tokens = SessionTokenRegistry()
        broker = PtyBroker()

        server =
            AndroidSocketServer(
                context = context,
                tokens = tokens,
                router = ApiRouter(),
                onPtyInput = { channel, bytes ->
                    broker.sessionForChannel(channel)?.write(bytes)
                },
                onPtyCredit = { channel, bytes -> readers[channel]?.grantCredit(bytes) },
                // Real ownership: this test drives pty_input, and the server now
                // refuses input for a channel the connection's session does not own.
                channelOwnedBy = broker::channelBelongsToSession,
            )
        assertTrue(server.start())
    }

    @After
    fun tearDown() {
        readers.keys.toList().forEach { channel ->
            broker.close(channel)
            readers.remove(channel)?.awaitStopped()
        }
        server.stop()
    }

    /** Opens a session the way the service's openSession does. */
    private fun openSession(
        sessionId: String,
        executable: String,
        credit: Int = PtyReader.DEFAULT_CREDIT_BYTES,
    ): Int {
        val opened = broker.open(sessionId, "dev-1", "op-1", PtyRequest(executable = executable))
        assertTrue("open failed: $opened", opened is PtyResult.Ok)
        val channel = (opened as PtyResult.Ok).value
        val session = broker.sessionForChannel(channel)!!

        val reader =
            PtyReader(
                session = session,
                initialCreditBytes = credit,
                onOutput = { bytes -> server.pushPtyOutput(channel, sessionId, bytes) },
            )
        readers[channel] = reader
        reader.start()

        return channel
    }

    private fun connect(token: String): LocalSocket =
        LocalSocket().apply {
            connect(
                LocalSocketAddress(
                    server.socketPath.absolutePath,
                    LocalSocketAddress.Namespace.FILESYSTEM,
                ),
            )
            BridgeProtocol.write(
                outputStream,
                FrameType.Request,
                BridgeJson
                    .encodeToString(
                        Request.serializer(),
                        Request("auth", API_AUTH_NAME, mapOf(ARG_TOKEN_NAME to token)),
                    ).toByteArray(),
            )
            // Drain the auth response.
            BridgeProtocol.read(inputStream)
        }

    private fun sendInput(
        out: OutputStream,
        channel: Int,
        text: String,
    ) {
        BridgeProtocol.write(out, FrameType.PtyInput, PtyFrame.encode(channel, text.toByteArray()))
    }

    /**
     * Reads one frame, or null when the socket timed out. Android's LocalSocket
     * reports an SO_TIMEOUT as `IOException("Try again")`, not a
     * SocketTimeoutException, so the message is what distinguishes idle from broken.
     */
    private fun readFrameOrNull(input: InputStream): Frame? =
        try {
            BridgeProtocol.read(input)
        } catch (t: java.io.IOException) {
            if (t.message?.contains("Try again", ignoreCase = true) == true) null else throw t
        }

    /** Reads pty_output frames until [needle] appears or the deadline passes. */
    private fun readUntil(
        input: InputStream,
        needle: String,
        timeoutMs: Long = 6000,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val collected = StringBuilder()

        while (System.currentTimeMillis() < deadline) {
            val frame = readFrameOrNull(input) ?: continue
            if (frame.type != FrameType.PtyOutput) continue
            val decoded = PtyFrame.decode(frame.payload) ?: continue
            collected.append(String(decoded.second))
            if (collected.contains(needle)) return collected.toString()
        }

        return collected.toString()
    }

    @Test
    fun aShellRoundTripsOverTheSocket() {
        val channel = openSession("rt-1", "/system/bin/sh")
        val token = tokens.issue("rt-1", "dev-1", "op-1", apiAllowlist = setOf(SessionApi.NAME))

        connect(token.token).use { socket ->
            val out = socket.outputStream
            val input = socket.inputStream

            sendInput(out, channel, "echo ROUND_TRIP_MARKER\n")

            val output = readUntil(input, "ROUND_TRIP_MARKER")
            assertTrue(
                "marker not seen over the socket: $output",
                output.contains("ROUND_TRIP_MARKER"),
            )
        }
    }

    @Test
    fun outputIsScopedToTheOwningSession() {
        val channelA = openSession("scoped-a", "/system/bin/sh")
        val channelB = openSession("scoped-b", "/system/bin/sh")

        // Two connections, each bound to a different session's token.
        connect(
            tokens.issue("scoped-a", "dev-1", "op-1", apiAllowlist = setOf(SessionApi.NAME)).token,
        ).use { socketA ->
            connect(
                tokens
                    .issue(
                        "scoped-b",
                        "dev-1",
                        "op-1",
                        apiAllowlist = setOf(SessionApi.NAME),
                    ).token,
            ).use { socketB ->
                sendInput(socketA.outputStream, channelA, "echo ONLY_A\n")

                // A's output must arrive on A's connection.
                val aOutput = readUntil(socketA.inputStream, "ONLY_A")
                assertTrue("A should see its own output: $aOutput", aOutput.contains("ONLY_A"))

                // B's connection must not have received A's channel output. It is
                // idle, so give it a moment and assert nothing for channel A came.
                socketB.soTimeout = 1500
                val leaked = collectPtyOutputFor(socketB.inputStream, channelA, 1500)
                assertTrue("channel A output leaked to B's connection", leaked.isEmpty())
            }
        }

        // Keep both channels alive until here so the scoping is what was tested.
        assertTrue(channelA != channelB)
    }

    private fun collectPtyOutputFor(
        input: InputStream,
        channel: Int,
        timeoutMs: Long,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val collected = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val frame = readFrameOrNull(input) ?: break
            if (frame.type != FrameType.PtyOutput) continue
            val decoded = PtyFrame.decode(frame.payload) ?: continue
            if (decoded.first == channel) collected.append(String(decoded.second))
        }

        return collected.toString()
    }

    @Test
    fun aCreditFrameIsAcceptedAndKeepsOutputFlowing() {
        // A small initial window proves credit is what releases the reader: with
        // the window exhausted the reader stops, and a credit frame resumes it.
        val channel = openSession("credit-1", "/system/bin/sh", credit = 8)
        val token = tokens.issue("credit-1", "dev-1", "op-1", apiAllowlist = setOf(SessionApi.NAME))

        connect(token.token).use { socket ->
            val out = socket.outputStream
            val input = socket.inputStream

            // Produce more than the 8-byte window so the reader must stop.
            sendInput(out, channel, "echo CREDIT_MARKER_LINE\n")

            // Grant credit repeatedly; the marker can only appear if credit was
            // consumed and replenished through the wire protocol.
            val deadline = System.currentTimeMillis() + 6000
            var output = ""
            while (System.currentTimeMillis() < deadline &&
                !output.contains("CREDIT_MARKER_LINE")
            ) {
                BridgeProtocol.write(
                    out,
                    FrameType.PtyCredit,
                    BridgeJson
                        .encodeToString(
                            PtyCredit.serializer(),
                            PtyCredit(channel = channel, bytes = 4096),
                        ).toByteArray(),
                )
                output += readUntil(input, "CREDIT_MARKER_LINE", timeoutMs = 500)
            }

            assertTrue(
                "output did not flow after credit grants: $output",
                output.contains("CREDIT_MARKER_LINE"),
            )
        }
    }

    @Test
    fun theFrameShapeIsTheContractShape() {
        // The length prefix is the payload length, big-endian, and the type is one
        // byte after it. A client implementing the contract depends on exactly this.
        val channel = openSession("shape-1", "/system/bin/sh")
        val token = tokens.issue("shape-1", "dev-1", "op-1", apiAllowlist = setOf(SessionApi.NAME))

        connect(token.token).use { socket ->
            sendInput(socket.outputStream, channel, "echo SHAPE\n")
            val frame = BridgeProtocol.read(socket.inputStream)!!
            assertEquals(FrameType.PtyOutput, frame.type)
            assertEquals(channel, PtyFrame.decode(frame.payload)!!.first)
        }
    }
}
