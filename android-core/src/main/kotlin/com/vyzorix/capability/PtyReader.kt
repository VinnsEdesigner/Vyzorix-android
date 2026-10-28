package com.vyzorix.capability

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * The reader for one PTY session.
 *
 * Contract §2.1: exactly one thread reads a master fd, and that thread closes it.
 * This is that thread. Closing an fd another thread is blocked on does not fail
 * cleanly — the number is released and can be handed to an unrelated file — so
 * [PtySession.close] only signals, and this loop performs the close once it has
 * stopped reading.
 *
 * Output is pushed through [onOutput] and gated by a credit window: the consumer
 * acknowledges bytes, and the reader stops reading when credit runs out. Without
 * it a `cat` of a large file grows the socket buffer without bound.
 */
class PtyReader(
    private val session: PtySession,
    private val initialCreditBytes: Int = DEFAULT_CREDIT_BYTES,
    private val onOutput: (ByteArray) -> Unit,
    private val onExit: (exitCode: Int) -> Unit = {},
) {
    private val credit = AtomicInteger(initialCreditBytes)
    private val running = AtomicBoolean(false)
    private val stopped = ReentrantLock()
    private val stoppedSignal: Condition = stopped.newCondition()
    private var finished = false

    /** Starts the reader. Idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) return

        Thread({ loop() }, "vyzorix-pty-${session.id}").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Adds credit. Called when the consumer acknowledges bytes. Capped so a
     * misbehaving consumer cannot inflate the window without bound.
     */
    fun grantCredit(bytes: Int) {
        if (bytes <= 0) return

        credit.updateAndGet { current ->
            val next = current + bytes
            if (next > MAX_CREDIT_BYTES) MAX_CREDIT_BYTES else next
        }
    }

    /**
     * Blocks until the reader has stopped and the fd is closed. Used by tests and
     * by shutdown, both of which need to know the descriptor is actually gone.
     */
    fun awaitStopped(timeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS): Boolean {
        stopped.lock()
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!finished && System.currentTimeMillis() < deadline) {
                stoppedSignal.await(
                    deadline - System.currentTimeMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
            }

            return finished
        } finally {
            stopped.unlock()
        }
    }

    private fun loop() {
        try {
            while (running.get() && !session.isClosed) {
                if (credit.get() <= 0) {
                    // Out of credit: sleep rather than spin. The consumer grants
                    // more when it has caught up.
                    Thread.sleep(CREDIT_WAIT_MS)

                    continue
                }

                when (val outcome = session.read(timeoutMs = READ_TIMEOUT_MS)) {
                    is PtyResult.Ok ->
                        when (val value = outcome.value) {
                            is PtyReadOutcome.Data -> {
                                credit.addAndGet(-value.bytes.size)
                                onOutput(value.bytes)
                            }
                            PtyReadOutcome.Timeout -> Unit
                            PtyReadOutcome.Eof -> return
                        }
                    is PtyResult.Err -> {
                        Log.d(TAG, "session ${session.id} read failed: ${outcome.error}")

                        return
                    }
                }
            }
        } catch (_: InterruptedException) {
            // A shutdown interrupt is a request to stop, not a failure.
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.d(TAG, "session ${session.id} reader stopped: ${t.message}")
        } finally {
            // The reader owns the fd, so it is the one that closes it.
            session.releaseFd()

            val exitCode = reap(session)

            notifyExit(exitCode)

            stopped.lock()
            try {
                finished = true
                stoppedSignal.signalAll()
            } finally {
                stopped.unlock()
            }
        }
    }

    /**
     * Reaps the child so it does not linger as a zombie, returning its exit code.
     *
     * `waitpid` is polled with `WNOHANG`; a blocking wait would hang on a shell
     * that never exits on its own. An interrupt here is honoured rather than
     * swallowed: the caller asked us to stop.
     */
    private fun reap(target: PtySession): Int {
        val deadline = System.currentTimeMillis() + REAP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val outcome = target.reapIfExited()
            if (outcome is PtyResult.Ok && outcome.value) {
                return target.childExitCode
            }

            try {
                Thread.sleep(REAP_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()

                return target.childExitCode
            }
        }

        return target.childExitCode
    }

    /** Runs the exit callback, isolating it so a throwing consumer cannot leak the fd path. */
    private fun notifyExit(exitCode: Int) {
        try {
            onExit(exitCode)
        } catch (t: Throwable) {
            Log.w(TAG, "session ${session.id} exit callback threw", t)
        }
    }

    companion object {
        const val DEFAULT_CREDIT_BYTES = 64 * 1024
        const val MAX_CREDIT_BYTES = 256 * 1024
        private const val READ_TIMEOUT_MS = 100
        private const val CREDIT_WAIT_MS = 20L
        private const val REAP_TIMEOUT_MS = 2000L
        private const val REAP_POLL_MS = 20L
        private const val DEFAULT_STOP_TIMEOUT_MS = 5000L
        private const val TAG = "PtyReader"
    }
}
