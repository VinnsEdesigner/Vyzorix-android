package com.vyzorix.service

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the engine and reports when it is gone.
 *
 * It does not restart the engine itself: restarting means re-exec'ing proot with
 * a fresh PTY, which only the service can do. The watchdog's job is to notice and
 * to back off, so a permanently broken engine does not spin.
 *
 * Backoff is exponential from [baseDelayMs] up to [maxDelayMs]; a single success
 * resets it, so a flapping engine is retried quickly but a dead one is not
 * hammered.
 */
class ServiceWatchdog(
    private val isEngineReady: () -> Boolean,
    private val onEngineLost: () -> Unit,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile private var consecutiveFailures = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return

        thread =
            Thread({ loop() }, "vyzorix-watchdog").apply {
                isDaemon = true
                start()
            }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return

        thread?.interrupt()
        thread = null
    }

    /** Consecutive failed checks. Visible for tests. */
    val failures: Int get() = consecutiveFailures

    private fun loop() {
        while (running.get()) {
            try {
                Thread.sleep(checkIntervalMs)

                if (isEngineReady()) {
                    consecutiveFailures = 0

                    continue
                }

                consecutiveFailures++
                Log.w(TAG, "engine not ready (failure $consecutiveFailures)")

                onEngineLost()
                Thread.sleep(backoffMs(consecutiveFailures))
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                Log.e(TAG, "watchdog check failed", t)
            }
        }
    }

    /** Exponential backoff, capped. */
    private fun backoffMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, MAX_SHIFT)

        return (baseDelayMs shl shift).coerceAtMost(maxDelayMs)
    }

    companion object {
        private const val TAG = "ServiceWatchdog"
        private const val DEFAULT_BASE_DELAY_MS = 1_000L
        private const val DEFAULT_MAX_DELAY_MS = 60_000L
        private const val DEFAULT_CHECK_INTERVAL_MS = 10_000L
        private const val MAX_SHIFT = 6
    }
}
