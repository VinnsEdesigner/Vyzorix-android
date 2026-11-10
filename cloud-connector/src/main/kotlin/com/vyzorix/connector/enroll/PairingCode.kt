package com.vyzorix.connector.enroll

/**
 * The pairing code the device shows and the operator types.
 *
 * The code is server-issued: the server owns the entropy, the TTL and the
 * single-use rule, and the device only displays it. This type models what the
 * device needs — the code, how it groups for reading, and when it expires — not
 * how it is generated.
 *
 * The alphabet is Crockford base32 (`0-9A-Z` minus `I L O U`), so a code read off a
 * notification cannot be mistyped for a different valid code.
 */
data class PairingCode(
    /** The code without the grouping separator, as the server issued it. */
    val code: String,
    /** The grouped form to display, `K7QM-3X2P`. */
    val display: String,
    /** Absolute expiry, Unix millis. */
    val expiresAt: Long,
    /**
     * Remaining seconds at the moment the code was received. The server sends this
     * alongside the absolute deadline so a device with a wrong clock still expires
     * the code correctly: the countdown uses this, not `expiresAt - now`.
     */
    val expiresInSeconds: Long,
) {
    /** True when the code is no longer claimable. */
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAt

    /** Seconds remaining, never negative. Uses the absolute deadline. */
    fun secondsRemaining(nowMs: Long): Long = ((expiresAt - nowMs) / 1000).coerceAtLeast(0)

    companion object {
        /**
         * Builds from the server's response fields. [display] falls back to grouping
         * [code] in fours if the server did not send one, so the notification never
         * shows an ungrouped code.
         */
        fun from(
            code: String,
            display: String,
            expiresAt: Long,
            expiresInSeconds: Long,
        ): PairingCode =
            PairingCode(
                code = code,
                display = display.ifEmpty { group(code) },
                expiresAt = expiresAt,
                expiresInSeconds = expiresInSeconds,
            )

        /** `K7QM3X2P` -> `K7QM-3X2P`. */
        fun group(code: String): String =
            if (code.length == CODE_LENGTH) {
                "${code.take(GROUP_SIZE)}-${code.drop(GROUP_SIZE)}"
            } else {
                code
            }

        /** The server's code length: 8 Crockford base32 characters, ~40 bits. */
        const val CODE_LENGTH = 8

        /** The display groups the code 4-4 so a human can read it off a notification. */
        private const val GROUP_SIZE = 4
    }
}
