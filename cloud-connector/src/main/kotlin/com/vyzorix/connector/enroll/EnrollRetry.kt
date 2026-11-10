package com.vyzorix.connector.enroll

import android.util.Log

/**
 * Repeats a failing enroll attempt until it succeeds, with bounded backoff.
 *
 * ## Why this exists
 *
 * `CloudConnection.start()` used to call `ensureEnrolled()` and then `openSocket()`
 * unconditionally. When enroll failed — no network, a rate limit, a server fault —
 * `ensureEnrolled()` returned without enrolling, and the socket opened anyway. The
 * socket's reconnect loop then retried forever against a device the server had never
 * heard of, so no pairing code was ever shown and the operator saw a notification
 * stuck on "running". The failure was silent and permanent.
 *
 * The fix is to treat enrollment as the precondition it is: retry it until it
 * succeeds, and only then open the socket. A device that cannot enroll is a device
 * with nothing to say to the server.
 *
 * ## What is retried, and what is not
 *
 * A **transport** failure (no network, DNS, TLS, timeout) and a **retryable server**
 * response (a rate limit, a 5xx) are retried: both are transient, and a phone that is
 * offline at boot is the normal case, not an error.
 *
 * A **validation** failure is not retried. The server refused the request on its
 * merits — a malformed key, a bad field — and repeating it only repeats the refusal.
 * Retrying it forever would hide a real bug behind a spinner, so it is reported once
 * and the loop stops.
 *
 * `isAlreadyClaimed` is not a failure at all: it means onboarding finished, so it is
 * handled by the caller and the loop stops.
 */
class EnrollRetry(
    /** How long to wait after attempt n, in milliseconds. Injected so a test does not sleep. */
    private val backoff: (attempt: Int) -> Long = ::defaultBackoff,
    /** Sleeps, injected for the same reason. */
    private val sleep: (millis: Long) -> Unit = { Thread.sleep(it) },
) {
    /**
     * Runs [attempt] until it reports success, a terminal refusal, or the caller stops.
     *
     * [shouldContinue] is polled between attempts, so a `stop()` during a long backoff
     * ends the loop rather than waiting it out — the service may be torn down while a
     * device is offline.
     *
     * Returns the outcome so the caller can distinguish "enrolled" from "already
     * claimed" (both proceed to the socket) from "gave up" (do not open the socket).
     */
    fun run(
        shouldContinue: () -> Boolean,
        attempt: () -> EnrollOutcome,
    ): EnrollOutcome {
        var n = 0

        while (shouldContinue()) {
            when (val outcome = attempt()) {
                EnrollOutcome.Enrolled, EnrollOutcome.AlreadyClaimed -> return outcome

                // Terminal: the server refused the request itself. Repeating it cannot
                // change the answer, so the loop stops and the caller does not open the
                // socket — there is nothing valid to say to the server.
                is EnrollOutcome.Rejected -> {
                    Log.w(TAG, "enroll refused and will not be retried: ${outcome.detail}")

                    return outcome
                }

                is EnrollOutcome.Transient -> {
                    n++
                    val wait = backoff(n)
                    Log.i(
                        TAG,
                        "enroll attempt $n failed (${outcome.detail}); retrying in ${wait}ms",
                    )
                    sleep(wait)
                }

                // `attempt` is called inside the `while (shouldContinue())` guard, so it
                // never returns Stopped: that outcome is produced by this loop, not by a
                // single attempt. Handling it here keeps the `when` exhaustive without an
                // `else` that would swallow a future outcome.
                EnrollOutcome.Stopped -> return EnrollOutcome.Stopped
            }
        }

        return EnrollOutcome.Stopped
    }

    private companion object {
        const val TAG = "EnrollRetry"

        /** The first retry waits this long; each later one doubles it. */
        const val BASE_DELAY_MS = 1000L

        /** The cap: the server advertises `backoff 1s>30s`, so the device matches it. */
        const val MAX_DELAY_MS = 30_000L

        /** 2^5 = 32, so the shift stops before it can overshoot the cap by much. */
        const val MAX_SHIFT = 5

        /** 1s, 2s, 4s, 8s, 16s, 32s→capped at 30s. */
        fun defaultBackoff(attempt: Int): Long {
            val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)

            return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
        }
    }
}

/** What one enroll attempt produced, in the terms the retry loop cares about. */
sealed interface EnrollOutcome {
    /** The server created (or refreshed) a pending record; a pairing code was shown. */
    data object Enrolled : EnrollOutcome

    /** The device is already claimed, so onboarding is done and there is no code. */
    data object AlreadyClaimed : EnrollOutcome

    /** The caller stopped the connection mid-retry. */
    data object Stopped : EnrollOutcome

    /** A transient failure: retry. */
    data class Transient(
        val detail: String,
    ) : EnrollOutcome

    /** A terminal refusal: do not retry. */
    data class Rejected(
        val detail: String,
    ) : EnrollOutcome
}
