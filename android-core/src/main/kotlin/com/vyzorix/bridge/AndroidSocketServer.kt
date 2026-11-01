package com.vyzorix.bridge

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vyzorix.capability.NativePty
import com.vyzorix.capability.PtyResult
import com.vyzorix.capability.asResult
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bridge: a unix socket at `files/android.sock` that answers API calls under
 * a per-session token.
 *
 * Ownership: one accept thread, one reader and one writer per connection, and a
 * bounded pool for API dispatch so a slow handler cannot stall framing. Bounds on
 * connections and dispatch threads exist so a client cannot exhaust the process.
 *
 * The socket lives in the **filesystem** namespace (contract §2.2), because the
 * Debian side must be able to bind it into the container's tree; an abstract name
 * is invisible to the filesystem and so cannot be bound into proot. Android has no
 * public Java API that binds a *listening* socket in the filesystem namespace —
 * `LocalServerSocket(String)` is abstract-only — so the bind happens natively and
 * the resulting descriptor is wrapped with [LocalServerSocket] via
 * [ParcelFileDescriptor.adoptFd].
 */
class AndroidSocketServer(
    private val context: Context,
    private val tokens: SessionTokenRegistry,
    private val router: ApiRouter,
    private val onPtyInput: (channel: Int, bytes: ByteArray) -> Unit,
    private val onPtyCredit: (channel: Int, bytes: Int) -> Unit,
    /**
     * True when [channel] belongs to [sessionId]. The device's binding check
     * (boundary contract §9): a connection may only drive the channels its own
     * session opened, so one operator cannot type into or credit another's
     * terminal. Defaulted to deny so a caller that forgets it fails closed.
     */
    private val channelOwnedBy: (channel: Int, sessionId: String) -> Boolean = { _, _ -> false },
    /**
     * This device's own identity, or null when it has not been provisioned yet.
     *
     * The contract's §9 clause `token.deviceId == self` needs the device to know
     * its own ID, which arrives with registration (Build 3). Until then this is
     * null and the check is skipped *loudly* — an unprovisioned device is reported
     * once at startup rather than silently accepting any token's device ID.
     */
    private val selfDeviceId: String? = null,
) {
    private val running = AtomicBoolean(false)
    private val connections = AtomicInteger(0)
    private val dispatch: ExecutorService = Executors.newFixedThreadPool(DISPATCH_THREADS)

    private var server: LocalServerSocket? = null
    private var listenOwner: ParcelFileDescriptor? = null
    private var acceptThread: Thread? = null
    private val live = CopyOnWriteArraySet<ClientConnection>()

    /** Path of the socket file. Bound into the container by the proot launcher. */
    val socketPath: File get() = File(context.filesDir, SOCKET_NAME)

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        return try {
            // A crashed process leaves the node behind, and binding over it fails.
            socketPath.delete()

            // The native library must be loaded before the bind is called. The PTY
            // paths load it through PtyBroker, but this call site is the first use
            // on the bridge's own path, so it loads it explicitly.
            val loaded = NativePty.loadLibrary()
            if (loaded is PtyResult.Err) {
                running.set(false)
                Log.e(TAG, "native library unavailable: ${loaded.error}")

                return false
            }

            val bound =
                NativePty
                    .bindListenSocket(socketPath.absolutePath)
                    .asResult { raw -> raw.fds?.getOrNull(0) ?: -1 }
            val fd = (bound as? PtyResult.Ok)?.value
            if (fd == null || fd < 0) {
                running.set(false)
                val reason = (bound as? PtyResult.Err)?.error?.toString() ?: "unknown"
                Log.e(TAG, "bridge failed to bind ${socketPath.absolutePath}: $reason")

                return false
            }
            // Restrict the node to the app's own uid. A filesystem socket is
            // reachable by path, so the mode is part of the access control; the
            // abstract namespace needed none.
            restrictToOwner(socketPath)

            // adoptFd takes ownership of the descriptor, so closing the
            // ParcelFileDescriptor closes it. LocalServerSocket(FileDescriptor)
            // does not own the fd, so the ParcelFileDescriptor is retained for the
            // lifetime of the server and closed in [stop].
            val owner = ParcelFileDescriptor.adoptFd(fd)
            listenOwner = owner
            server = LocalServerSocket(owner.fileDescriptor)

            acceptThread =
                Thread({ acceptLoop() }, "vyzorix-accept").apply {
                    isDaemon = true
                    start()
                }

            Log.i(TAG, "bridge listening on ${socketPath.absolutePath}")
            if (selfDeviceId == null) {
                // Stated once, loudly: the §9 device-identity clause is not enforced
                // because the device does not know its own ID yet (registration is
                // Build 3). Silence here would read as "enforced".
                Log.w(
                    TAG,
                    "device identity unset: tokens are not bound to this device " +
                        "(registration lands in Build 3)",
                )
            }

            true
        } catch (t: Throwable) {
            running.set(false)
            Log.e(TAG, "bridge failed to start", t)

            false
        }
    }

    private fun restrictToOwner(path: File) {
        runCatching {
            path.setReadable(false, false)
            path.setWritable(false, false)
            path.setReadable(true, true)
            path.setWritable(true, true)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return

        runCatching { server?.close() }
        runCatching { listenOwner?.close() }
        listenOwner = null
        server = null
        live.forEach { it.close() }
        live.clear()
        dispatch.shutdownNow()
        socketPath.delete()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket =
                try {
                    server?.accept()
                } catch (t: Throwable) {
                    if (running.get()) Log.w(TAG, "accept failed", t)

                    break
                } ?: break

            if (connections.get() >= MAX_CONNECTIONS) {
                Log.w(TAG, "connection refused: at capacity")
                runCatching { socket.close() }

                continue
            }

            connections.incrementAndGet()
            val connection = ClientConnection(socket)
            live.add(connection)

            Thread({ connection.run() }, "vyzorix-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Pushes PTY output for [channel] to the connections entitled to it: those
     * bound to the session that owns the channel. Broadcasting to every connection
     * would leak one session's terminal to another operator.
     */
    fun pushPtyOutput(
        channel: Int,
        sessionId: String,
        bytes: ByteArray,
    ) {
        val payload = PtyFrame.encode(channel, bytes)
        live.forEach { if (it.ownsSession(sessionId)) it.send(FrameType.PtyOutput, payload) }
    }

    /**
     * Pushes an unsolicited event to every authenticated connection. Used for
     * session lifecycle notices, which carry no other session's data.
     */
    fun pushEvent(
        topic: String,
        data: String,
    ) {
        val json = BridgeJson.encodeToString(Event.serializer(), Event(topic, data))
        val payload = json.toByteArray(Charsets.UTF_8)
        live.forEach { if (it.isAuthenticated()) it.send(FrameType.Event, payload) }
    }

    /**
     * One client connection. Reads frames, enforces authentication, dispatches
     * requests, and serializes writes through a queue so the reader and a PTY
     * push cannot interleave half a frame.
     */
    private inner class ClientConnection(
        private val socket: LocalSocket,
    ) {
        private val closed = AtomicBoolean(false)
        private val outbox = LinkedBlockingQueue<Pair<FrameType, ByteArray>>(MAX_OUTBOX_FRAMES)

        @Volatile private var token: SessionToken? = null
        private var writer: Thread? = null

        /** True once this connection has presented a valid token. */
        fun isAuthenticated(): Boolean = token != null

        /** True when this connection is bound to [sessionId]. */
        fun ownsSession(sessionId: String): Boolean = token?.sessionId == sessionId

        fun run() {
            writer =
                Thread({ writeLoop() }, "vyzorix-writer").apply {
                    isDaemon = true
                    start()
                }

            try {
                // SO_TIMEOUT bounds a blocking read, so a connection that never
                // authenticates is closed at the deadline even if it sends nothing.
                socket.soTimeout = AUTH_TIMEOUT_MS.toInt()

                val input = socket.inputStream
                val deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS

                while (!closed.get()) {
                    val frame =
                        try {
                            BridgeProtocol.read(input)
                        } catch (timeout: IOException) {
                            // Android's LocalSocket raises a plain IOException on an
                            // SO_TIMEOUT (native EAGAIN), not a SocketTimeoutException,
                            // so the timeout is recognised by message. A timeout before
                            // authentication means the client has been silent past the
                            // deadline; after it, an idle connection is fine.
                            if (!isTimeout(timeout)) throw timeout

                            if (token != null) continue

                            break
                        } ?: break

                    if (token == null) {
                        if (!handlePreAuth(frame, deadline)) break
                    } else {
                        handleAuthenticated(frame)
                    }
                }
            } catch (t: Throwable) {
                if (closed.get().not()) Log.d(TAG, "connection ended: ${t.message}")
            } finally {
                close()
            }
        }

        /**
         * Before authentication, only an auth request is served. Anything else, or
         * silence past the deadline, closes the connection so an idle socket cannot
         * hold a slot.
         */
        private fun handlePreAuth(
            frame: Frame,
            deadline: Long,
        ): Boolean {
            if (System.currentTimeMillis() > deadline) return false
            if (frame.type != FrameType.Request) return false

            val request =
                runCatching { BridgeJson.decodeFromString<Request>(decode(frame.payload)) }
                    .getOrNull() ?: return false

            if (request.api != API_AUTH_NAME) return false

            val presented = request.args[ARG_TOKEN_NAME] ?: return false
            val validated = tokens.validate(presented) ?: return false

            // §9 binding: the token must be for THIS device. When the device is not
            // yet provisioned there is no self ID to compare, and the token is
            // accepted on the strength of the token alone; start() logs that state
            // once so it is visible rather than assumed.
            if (selfDeviceId != null && validated.deviceId != selfDeviceId) {
                Log.w(TAG, "token for device ${validated.deviceId} presented to $selfDeviceId")
                return false
            }

            token = validated
            // Past the handshake a connection may idle, so the pre-auth deadline
            // no longer applies.
            runCatching { socket.soTimeout = 0 }
            send(
                FrameType.Response,
                encode(
                    BridgeJson.encodeToString(
                        Response.serializer(),
                        Response(
                            id = request.id,
                            ok = true,
                            result =
                                BridgeJson.encodeToString(
                                    AuthResult.serializer(),
                                    AuthResult(session = validated.sessionId),
                                ),
                        ),
                    ),
                ),
            )

            return true
        }

        private fun handleAuthenticated(frame: Frame) {
            when (frame.type) {
                FrameType.Request -> {
                    val request =
                        runCatching { BridgeJson.decodeFromString<Request>(decode(frame.payload)) }
                            .getOrNull() ?: return

                    val active = token ?: return
                    dispatch.execute { serve(active, request) }
                }
                FrameType.PtyInput -> {
                    val decoded = PtyFrame.decode(frame.payload) ?: return
                    val active = token ?: return
                    // Binding check (§9): only the session that opened the channel
                    // may type into it.
                    if (!active.permitsChannel(decoded.first, channelOwnedBy)) return
                    onPtyInput(decoded.first, decoded.second)
                }
                FrameType.PtyCredit -> {
                    val credit =
                        runCatching {
                            BridgeJson.decodeFromString<PtyCredit>(
                                decode(frame.payload),
                            )
                        }.getOrNull() ?: return

                    val active = token ?: return
                    if (!active.permitsChannel(credit.channel, channelOwnedBy)) return
                    onPtyCredit(credit.channel, credit.bytes)
                }
                else -> Unit
            }
        }

        private fun serve(
            active: SessionToken,
            request: Request,
        ) {
            val outcome = router.dispatch(context, active, request.api, request.args)

            val response =
                if (outcome.isOk) {
                    Response(id = request.id, ok = true, result = outcome.json)
                } else {
                    Response(id = request.id, ok = false, error = outcome.error)
                }

            send(
                FrameType.Response,
                encode(BridgeJson.encodeToString(Response.serializer(), response)),
            )
        }

        fun send(
            type: FrameType,
            payload: ByteArray,
        ) {
            if (closed.get()) return

            // A client that stops reading must not grow this queue without bound:
            // when it is full the connection is dropped rather than the process
            // being exhausted. A dropped slow consumer can reconnect.
            if (!outbox.offer(type to payload)) {
                Log.w(TAG, "outbox full; closing connection")
                close()
            }
        }

        private fun writeLoop() {
            try {
                val output = socket.outputStream
                while (!closed.get()) {
                    val item = outbox.poll(WRITE_POLL_MS, TimeUnit.MILLISECONDS) ?: continue

                    BridgeProtocol.write(output, item.first, item.second)
                }
            } catch (t: Throwable) {
                if (closed.get().not()) Log.d(TAG, "writer ended: ${t.message}")
            } finally {
                close()
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return

            connections.decrementAndGet()
            live.remove(this)
            runCatching { socket.close() }
        }

        private fun decode(payload: ByteArray): String = String(payload, Charsets.UTF_8)

        private fun encode(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "VyzorixBridge"
        const val SOCKET_NAME = "android.sock"
        const val MAX_CONNECTIONS = 16
        const val DISPATCH_THREADS = 4
        const val AUTH_TIMEOUT_MS = 5_000L
        const val WRITE_POLL_MS = 250L
        const val MAX_OUTBOX_FRAMES = 256

        /**
         * True when [t] is an SO_TIMEOUT from Android's [LocalSocket].
         *
         * The platform raises `IOException("Try again")` on a read timeout rather
         * than [java.net.SocketTimeoutException], so the message is the only
         * reliable signal. Matching it is deliberate: treating a timeout as a
         * fatal read error would drop an idle-but-healthy connection.
         */
        fun isTimeout(t: IOException): Boolean =
            t.message?.contains("Try again", ignoreCase = true) == true
    }
}
