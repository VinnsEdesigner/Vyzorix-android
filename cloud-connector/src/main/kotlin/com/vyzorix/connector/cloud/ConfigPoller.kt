package com.vyzorix.connector.cloud

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Asks the server for the config on a cadence while the device waits to be claimed.
 *
 * The config normally arrives over the held WebSocket the moment a user claims
 * the pairing code, so this is a fallback, not the primary path. It exists for the
 * window where the socket is not up — the device enrolled but is not yet connected,
 * or the socket is reconnecting — so a claimed device does not sit unconfigured
 * until something else happens to trigger a fetch.
 *
 * The cadence comes from the enroll response, so the server owns it. The poller
 * stops the first time a fetch reports a config, and is never started again: once
 * configured, the socket push and the reconnect-time version check keep it current.
 */
class ConfigPoller(
    private val intervalSeconds: Long,
    /** Fetches the config once. Returns true when the device now holds one. */
    private val fetch: () -> Boolean,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** How many polls have run. Exposed for tests and diagnostics. */
    val pollCount = AtomicLong(0)

    init {
        require(intervalSeconds > 0) { "poll interval must be positive" }
    }

    /** Starts polling. Idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) return

        thread =
            Thread({ loop() }, "vyzorix-config-poll").apply {
                isDaemon = true
                start()
            }
    }

    /** Stops polling. Called once the device is configured, or on shutdown. */
    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    // A failed fetch is the expected state while offline: the poller's whole job is
    // to retry, so the exception carries nothing the caller can act on.
    @Suppress("SwallowedException")
    private fun loop() {
        while (running.get()) {
            // Fetch first, then sleep: the first poll runs immediately, so a device
            // that was just claimed does not wait a full interval.
            val configured =
                try {
                    fetch()
                } catch (e: Exception) {
                    // A failed fetch is expected while offline; the next tick retries.
                    false
                }
            pollCount.incrementAndGet()

            if (configured) {
                // Configured: the push path and the reconnect check own it from here.
                stop()

                return
            }

            var slept = 0L
            while (running.get() && slept < intervalSeconds * MILLIS_PER_SECOND) {
                try {
                    Thread.sleep(SLEEP_SLICE_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()

                    return
                }
                slept += SLEEP_SLICE_MS
            }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val SLEEP_SLICE_MS = 250L
    }
}
