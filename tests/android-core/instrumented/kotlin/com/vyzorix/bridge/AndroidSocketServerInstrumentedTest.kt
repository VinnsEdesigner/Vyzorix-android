package com.vyzorix.bridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyzorix.bridge.apis.BatteryApi
import com.vyzorix.bridge.apis.LogcatApi
import com.vyzorix.capability.NativePty
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bridge end to end, on a device: a real socket, a real token, real frames.
 *
 * These are the tests that answer definition-of-done items 1-4: a client connects,
 * authenticates, calls an API, and an unauthenticated or out-of-scope call is
 * refused.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSocketServerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var tokens: SessionTokenRegistry
    private lateinit var server: AndroidSocketServer

    @Before
    fun setUp() {
        tokens = SessionTokenRegistry()
        server =
            AndroidSocketServer(
                context = context,
                tokens = tokens,
                // Both APIs are registered so the scope check is what refuses a
                // scoped call, rather than the name not resolving.
                router =
                    ApiRouter()
                        .register(BatteryApi.NAME, BatteryApi)
                        .register(LogcatApi.NAME, LogcatApi),
                onPtyInput = { _, _ -> },
                onPtyCredit = { _, _ -> },
            )
        assertTrue("server must bind", server.start())
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun connect(): LocalSocket =
        LocalSocket().apply {
            connect(
                LocalSocketAddress(
                    server.socketPath.absolutePath,
                    LocalSocketAddress.Namespace.FILESYSTEM,
                ),
            )
        }

    private fun send(
        out: OutputStream,
        type: FrameType,
        payload: ByteArray,
    ) {
        BridgeProtocol.write(out, type, payload)
    }

    private fun sendRequest(
        out: OutputStream,
        id: String,
        api: String,
        args: Map<String, String> = emptyMap(),
    ) {
        val json = BridgeJson.encodeToString(Request.serializer(), Request(id, api, args))
        send(out, FrameType.Request, json.toByteArray())
    }

    private fun readResponse(input: InputStream): Response {
        val frame = BridgeProtocol.read(input) ?: error("stream closed")
        assertEquals(FrameType.Response, frame.type)

        return BridgeJson.decodeFromString(String(frame.payload))
    }

    @Test
    fun socketIsBoundInTheFilesystemNamespace() {
        // Contract 2.2: a filesystem path is what proot can bind into the
        // container. The node must exist on disk.
        assertTrue(server.socketPath.exists())
    }

    @Test
    fun validTokenAuthenticatesAndAScopedCallAnswers() {
        val token =
            tokens.issue(
                "s1",
                "dev-1",
                "op-1",
                apiAllowlist = setOf(BatteryApi.NAME, LogcatApi.NAME),
            )

        connect().use { socket ->
            val out = socket.outputStream
            val input = socket.inputStream

            sendRequest(out, "1", API_AUTH_NAME, mapOf(ARG_TOKEN_NAME to token.token))
            val auth = readResponse(input)
            assertTrue(auth.ok)

            sendRequest(out, "2", BatteryApi.NAME)
            val battery = readResponse(input)
            assertTrue("battery call failed: ${battery.error}", battery.ok)
            assertTrue(battery.result!!.contains("level"))
        }
    }

    @Test
    fun connectionWithNoTokenIsClosedWithoutServingARequest() {
        connect().use { socket ->
            val out = socket.outputStream
            val input = socket.inputStream

            // A request before auth must not be served.
            sendRequest(out, "1", BatteryApi.NAME)

            val frame = runCatching { BridgeProtocol.read(input) }.getOrNull()
            assertTrue("no response should arrive before auth, got $frame", frame == null)
        }
    }

    @Test
    fun badTokenIsRefused() {
        connect().use { socket ->
            val out = socket.outputStream
            val input = socket.inputStream

            sendRequest(out, "1", API_AUTH_NAME, mapOf(ARG_TOKEN_NAME to "bogus"))

            val frame = runCatching { BridgeProtocol.read(input) }.getOrNull()
            assertTrue("a bad token must not authenticate, got $frame", frame == null)
        }
    }

    @Test
    fun aTokenForAnotherDeviceIsRefused() {
        // §9: the token must be for THIS device. A valid, correctly-scoped token
        // minted for a different device must not authenticate — otherwise a token
        // leaked from another device opens this one.
        val foreign =
            tokens.issue(
                "s1",
                "some-other-device",
                "op-1",
                apiAllowlist = setOf(BatteryApi.NAME),
            )
        val bound =
            AndroidSocketServer(
                context = context,
                tokens = tokens,
                router = ApiRouter().register(BatteryApi.NAME, BatteryApi),
                onPtyInput = { _, _ -> },
                onPtyCredit = { _, _ -> },
                selfDeviceId = { "this-device" },
            )
        assertTrue(bound.start())
        try {
            LocalSocket()
                .apply {
                    connect(
                        LocalSocketAddress(
                            bound.socketPath.absolutePath,
                            LocalSocketAddress.Namespace.FILESYSTEM,
                        ),
                    )
                }.use { socket ->
                    val out = socket.outputStream
                    val input = socket.inputStream

                    sendRequest(out, "1", API_AUTH_NAME, mapOf(ARG_TOKEN_NAME to foreign.token))

                    val frame = runCatching { BridgeProtocol.read(input) }.getOrNull()
                    assertTrue(
                        "a foreign device's token must be refused, got $frame",
                        frame == null,
                    )
                }
        } finally {
            bound.stop()
        }
    }

    @Test
    fun theBindingAppliesToAnIdThatArrivesAfterStart() {
        // The id is a provider because enrollment finishes after the server is built.
        // A device that enrolls while the bridge is already up must be bound from that
        // moment, not left unbound for the life of the process.
        var self: String? = null
        val late =
            AndroidSocketServer(
                context = context,
                tokens = tokens,
                router = ApiRouter().register(BatteryApi.NAME, BatteryApi),
                onPtyInput = { _, _ -> },
                onPtyCredit = { _, _ -> },
                selfDeviceId = { self },
            )
        assertTrue(late.start())
        try {
            val foreign =
                tokens.issue(
                    "s1",
                    "some-other-device",
                    "op-1",
                    apiAllowlist = setOf(BatteryApi.NAME),
                )

            // Unprovisioned: no self id, so the token is accepted on its own merit.
            LocalSocket()
                .apply {
                    connect(
                        LocalSocketAddress(
                            late.socketPath.absolutePath,
                            LocalSocketAddress.Namespace.FILESYSTEM,
                        ),
                    )
                }.use { socket ->
                    sendRequest(
                        socket.outputStream,
                        "1",
                        API_AUTH_NAME,
                        mapOf(ARG_TOKEN_NAME to foreign.token),
                    )
                    val auth = readResponse(socket.inputStream)
                    assertTrue("unprovisioned: the token authenticates on its own", auth.ok)
                }

            // Now the device enrolls. The same foreign token must be refused.
            self = "this-device"
            LocalSocket()
                .apply {
                    connect(
                        LocalSocketAddress(
                            late.socketPath.absolutePath,
                            LocalSocketAddress.Namespace.FILESYSTEM,
                        ),
                    )
                }.use { socket ->
                    sendRequest(
                        socket.outputStream,
                        "1",
                        API_AUTH_NAME,
                        mapOf(ARG_TOKEN_NAME to foreign.token),
                    )
                    val frame = runCatching { BridgeProtocol.read(socket.inputStream) }.getOrNull()
                    assertTrue(
                        "once provisioned, the foreign token must be refused, got $frame",
                        frame == null,
                    )
                }
        } finally {
            late.stop()
        }
    }

    @Test
    fun tokenScopedToBatteryIsRefusedLogcat() {
        val token = tokens.issue("s1", "dev-1", "op-1", apiAllowlist = setOf(BatteryApi.NAME))

        connect().use { socket ->
            val out = socket.outputStream
            val input = socket.inputStream

            sendRequest(out, "1", API_AUTH_NAME, mapOf(ARG_TOKEN_NAME to token.token))
            assertTrue(readResponse(input).ok)

            sendRequest(out, "2", "logcat")
            val refused = readResponse(input)
            assertFalse(refused.ok)
            assertEquals(BridgeErrorKind.FORBIDDEN, refused.error?.kind)
        }
    }

    @Test
    fun unknownApiReportsUnknownApi() {
        val token =
            tokens.issue(
                "s1",
                "dev-1",
                "op-1",
                apiAllowlist = setOf(BatteryApi.NAME, LogcatApi.NAME),
            )

        connect().use { socket ->
            val out = socket.outputStream
            val input = socket.inputStream

            sendRequest(out, "1", API_AUTH_NAME, mapOf(ARG_TOKEN_NAME to token.token))
            readResponse(input)

            sendRequest(out, "2", "nope")
            val response = readResponse(input)
            assertEquals(BridgeErrorKind.UNKNOWN_API, response.error?.kind)
        }
    }

    @Test
    fun twoConnectionsCanUseTheSameToken() {
        val token =
            tokens.issue(
                "s1",
                "dev-1",
                "op-1",
                apiAllowlist = setOf(BatteryApi.NAME, LogcatApi.NAME),
            )

        repeat(2) {
            connect().use { socket ->
                val out = socket.outputStream
                val input = socket.inputStream

                sendRequest(out, "1", API_AUTH_NAME, mapOf(ARG_TOKEN_NAME to token.token))
                assertTrue(readResponse(input).ok)
            }
        }
    }

    @Test
    fun nativeBindRefusesAPathTooLongForSunPath() {
        val result = NativePty.bindListenSocket("/data/" + "a".repeat(200))

        assertFalse(result.isOk)
        assertEquals(2, result.status) // InvalidArgument
    }

    @Test
    fun nativeBindCreatesAFilesystemNode() {
        val path = java.io.File(context.filesDir, "bind_probe.sock")
        path.delete()

        val result = NativePty.bindListenSocket(path.absolutePath)

        try {
            assertTrue("bind failed: ${result.detail}", result.isOk)
            assertTrue(path.exists())
            val fd = result.fds!![0]
            assertTrue(fd >= 0)
            NativePty.closeFd(fd)
        } finally {
            path.delete()
        }
    }

    @Test
    fun malformedFrameClosesTheConnection() {
        connect().use { socket ->
            val out = socket.outputStream
            val input = socket.inputStream

            // Declare a payload larger than the cap.
            val header = java.nio.ByteBuffer.allocate(5)
            header.putInt(BridgeProtocol.MAX_PAYLOAD + 1)
            header.put(FrameType.Request.code)
            out.write(header.array())
            out.flush()

            val frame = runCatching { BridgeProtocol.read(input) }.getOrNull()
            assertTrue("an oversized frame must close the connection, got $frame", frame == null)
        }
    }

    @Test
    fun eventIsPushedOnlyToAuthenticatedConnections() {
        val token =
            tokens.issue(
                "s1",
                "dev-1",
                "op-1",
                apiAllowlist = setOf(BatteryApi.NAME, LogcatApi.NAME),
            )

        connect().use { socket ->
            val out = socket.outputStream
            val input = socket.inputStream

            sendRequest(out, "1", API_AUTH_NAME, mapOf(ARG_TOKEN_NAME to token.token))
            readResponse(input)

            server.pushEvent("test.topic", JSONObject().put("k", "v").toString())

            val frame = BridgeProtocol.read(input)!!
            assertEquals(FrameType.Event, frame.type)
            val event = BridgeJson.decodeFromString<Event>(String(frame.payload))
            assertEquals("test.topic", event.topic)
        }
    }
}
