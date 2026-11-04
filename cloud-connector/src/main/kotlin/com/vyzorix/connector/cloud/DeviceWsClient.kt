package com.vyzorix.connector.cloud

import com.vyzorix.connector.identity.DeviceSigner
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * The device's WebSocket to the server. **The single cloud WS owner** (architecture
 * §5): the supervisor and the PTY agent never dial the cloud; they go through this.
 *
 * Responsibilities:
 *  - connect with a signed handshake, retrying with exponential backoff;
 *  - heartbeat, so an idle connection is not reaped;
 *  - hand inbound text frames to [onTextFrame] and binary PTY frames to [onPtyFrame];
 *  - let the caller send text (command results, telemetry) and binary (PTY) frames.
 *
 * The handshake signature travels as query parameters because a WebSocket upgrade
 * cannot set the signature headers. It is base64url for the same reason — a `+`
 * decodes as a space in a query string. See `Signatures.connect`.
 *
 * Reconnect is deliberately simple and correct: one connection at a time, backoff
 * bounded, and every callback carries the generation so a late frame from a dead
 * socket is ignored rather than acted on.
 */
class DeviceWsClient(
    private val baseUrl: String,
    private val deviceId: String,
    private val signer: DeviceSigner,
    private val http: OkHttpClient,
    private val listener: Listener,
    private val backoff: Backoff = Backoff(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Callbacks for inbound traffic and lifecycle. */
    interface Listener {
        /** A text frame arrived. Its exact bytes matter; they are handed as-is. */
        fun onTextFrame(raw: String)

        /** A binary PTY frame arrived. */
        fun onPtyFrame(bytes: ByteArray)

        /** The connection is up. Used to re-fetch the config by version. */
        fun onConnected()

        /** The connection dropped; a reconnect is scheduled. */
        fun onDisconnected(
            reason: String,
            willRetry: Boolean,
        )
    }

    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private var socket: WebSocket? = null

    /** Opens the connection. Idempotent: a second call while running is a no-op. */
    fun connect() {
        if (!running.compareAndSet(false, true)) return

        openSocket(generation.get())
    }

    /** Closes the connection and stops reconnecting. */
    fun close() {
        running.set(false)
        socket?.close(NORMAL_CLOSURE, "client closing")
        socket = null
    }

    /** Sends a text frame. Returns false when there is no live socket. */
    fun sendText(text: String): Boolean = socket?.send(text) ?: false

    /** Sends a binary frame (PTY traffic). Returns false when there is no live socket. */
    fun sendBinary(bytes: ByteArray): Boolean = socket?.send(bytes.toByteString()) ?: false

    private fun openSocket(gen: Long) {
        val request = buildRequest()
        socket = http.newWebSocket(request, SocketListener(gen))
    }

    /**
     * Builds the upgrade request with the signed handshake.
     *
     * The signature covers `connect|deviceId|timestamp|nonce` and is base64url. The
     * parameters are URL-encoded so nothing in the signature is reinterpreted by
     * query parsing.
     */
    internal fun buildRequest(): Request {
        val timestamp = clock().toString()
        val nonce = DeviceRequestSigner.randomNonce()
        val canonical = Signatures.connect(deviceId, timestamp, nonce)
        val signature = signer.sign(canonical, urlSafe = true)

        val url =
            "$baseUrl/v1/device/$deviceId/stream" +
                "?sig_timestamp=${encode(timestamp)}" +
                "&sig_nonce=${encode(nonce)}" +
                "&sig_signature=${encode(signature)}"

        return Request.Builder().url(url).build()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private inner class SocketListener(
        private val gen: Long,
    ) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            // A successful connection resets the backoff, so a later outage starts
            // from the floor rather than the previous maximum.
            backoff.reset()
            listener.onConnected()
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (gen != generation.get()) return
            listener.onTextFrame(text)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            if (gen != generation.get()) return
            listener.onPtyFrame(bytes.toByteArray())
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            handleDrop(gen, "closed: $code $reason")
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            handleDrop(gen, "failure: ${t.message}")
        }

        private fun handleDrop(
            gen: Long,
            reason: String,
        ) {
            // A frame from a superseded generation is ignored: only the live socket
            // schedules a reconnect, so a storm of failures cannot open several.
            if (gen != generation.get()) return

            socket = null

            if (!running.get()) {
                listener.onDisconnected(reason, willRetry = false)

                return
            }

            generation.incrementAndGet()
            val delay = backoff.next()
            listener.onDisconnected(reason, willRetry = true)
            scheduleReconnect(delay)
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        // A dedicated thread rather than a coroutine scope: the client owns no scope,
        // and the reconnect must outlive any caller. The generation guard means a
        // close() during the wait cancels the reconnect.
        val gen = generation.get()
        Thread {
            try {
                Thread.sleep(delayMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()

                return@Thread
            }

            if (running.get() && gen == generation.get()) {
                openSocket(gen)
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val NORMAL_CLOSURE = 1000
    }
}

/**
 * Bounded exponential backoff with jitter.
 *
 * Bounded because an unbounded one would leave a device silent for hours after a
 * brief outage; jittered so a fleet coming back from a server restart does not
 * reconnect in lockstep. The server advertises `backoff 1s>30s` in the config;
 * these are the defaults until it does.
 */
class Backoff(
    private val minMs: Long = 1_000,
    private val maxMs: Long = 30_000,
    private val random: java.util.Random = java.util.Random(),
) {
    private var current = minMs

    /** The next delay and advances the sequence. */
    fun next(): Long {
        val base = current
        current = (current * 2).coerceAtMost(maxMs)

        // Full jitter: a uniform draw in [base/2, base].
        val jittered = base / 2 + (random.nextDouble() * (base / 2)).toLong()

        return jittered
    }

    /** Resets after a successful connection, so the next outage starts from the floor. */
    fun reset() {
        current = minMs
    }
}
