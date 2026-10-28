package com.vyzorix.bridge

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A capability granted to one spawned session.
 *
 * [apiAllowlist] is the scope: a token issued for a shell that may read the
 * battery cannot also read logcat. Empty means every API, which is what an
 * operator-initiated terminal gets.
 */
class SessionToken internal constructor(
    val token: String,
    val sessionId: String,
    val deviceId: String,
    val operatorId: String,
    val apiAllowlist: Set<String>,
    val expiresAtMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val revoked = AtomicBoolean(false)

    val isRevoked: Boolean get() = revoked.get()

    val isExpired: Boolean get() = clock() > expiresAtMs

    val isValid: Boolean get() = !isRevoked && !isExpired

    /** True when this token may call [api]. An empty allowlist permits every API. */
    fun permits(api: String): Boolean = apiAllowlist.isEmpty() || api in apiAllowlist

    internal fun revoke() {
        revoked.set(true)
    }
}

/**
 * Issues and validates session tokens.
 *
 * A token is presented once per connection (contract §2.5); after that the
 * connection carries the capability. It is reusable across connections for the
 * lifetime of the session, because a shell that runs `android-battery` twice
 * opens two connections and should not need two tokens.
 */
class SessionTokenRegistry(
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val byToken = ConcurrentHashMap<String, SessionToken>()

    /**
     * Issues a token for a session. [apiAllowlist] empty means every API.
     */
    fun issue(
        sessionId: String,
        deviceId: String,
        operatorId: String,
        apiAllowlist: Set<String> = emptySet(),
        ttlMs: Long = DEFAULT_TTL_MS,
    ): SessionToken {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)

        val token =
            SessionToken(
                token = bytes.toHex(),
                sessionId = sessionId,
                deviceId = deviceId,
                operatorId = operatorId,
                apiAllowlist = apiAllowlist,
                expiresAtMs = clock() + ttlMs,
                clock = clock,
            )

        byToken[token.token] = token

        return token
    }

    /**
     * Resolves a presented token. Returns null when it is unknown, revoked, or
     * expired — the caller cannot tell which, and does not need to.
     */
    fun validate(token: String): SessionToken? {
        val found = byToken[token] ?: return null
        if (!found.isValid) {
            byToken.remove(token)

            return null
        }

        return found
    }

    /** Revokes every token for a session and returns how many were removed. */
    fun revokeSession(sessionId: String): Int {
        var removed = 0
        for ((key, token) in byToken) {
            if (token.sessionId != sessionId) continue
            token.revoke()
            byToken.remove(key)
            removed++
        }

        return removed
    }

    /** Drops expired and revoked tokens. Returns how many were removed. */
    fun sweep(): Int {
        val now = clock()
        var removed = 0
        for ((key, token) in byToken) {
            if (!token.isRevoked && token.expiresAtMs >= now) continue
            byToken.remove(key)
            removed++
        }

        return removed
    }

    val size: Int get() = byToken.size

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val TOKEN_BYTES = 16
        const val DEFAULT_TTL_MS = 12 * 60 * 60 * 1000L
    }
}
