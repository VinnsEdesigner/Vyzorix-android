package com.vyzorix.service

import kotlinx.serialization.Serializable

/**
 * The user a terminal session belongs to, and what they may call.
 *
 * This is the server's resolved decision, delivered with the session — not a claim
 * by the client. Two things read it:
 *
 *  - the **device** enforces [allowlist] at the bridge (a call to an API outside it
 *    is refused), and
 *  - the **Vyzorix CLI** inside the container shows the user and offers only
 *    what they may call, so the terminal is a session with an identity rather than
 *    an anonymous command dump.
 *
 * It lives here, in the core, because both sides need it: the connector decodes it
 * from the OPEN frame and the service delivers it to the shell. Putting it in either
 * would make the other depend the wrong way.
 *
 * [role] is the machine value (`operator`, `admin`, …); [roleLabel] is its human
 * name (`User`, `Administrator`) so a display need not map the two.
 */
@Serializable
data class SessionIdentity(
    val user: String = "",
    val name: String = "",
    val email: String = "",
    val org: String = "",
    val orgName: String = "",
    val role: String = "",
    val roleLabel: String = "",
    val tier: Int = 0,
    val expiresAt: Long = 0,
    val allowlist: List<String> = emptyList(),
) {
    /**
     * This identity as the JSON the container's shell receives, **with no newline**.
     *
     * The value goes into proot's argv as `VYZORIX_SESSION=<json>`. kotlinx emits no
     * pretty newlines, but this asserts it: a newline inside the value would be a way
     * to smuggle a second `KEY=VALUE` into the argv list, so the one place that builds
     * the string guarantees it cannot.
     */
    fun toJson(): String =
        json
            .encodeToString(serializer(), this)
            .replace("\n", "")
            .replace("\r", "")

    companion object {
        private val json =
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        /**
         * Parses the identity JSON the server sends with an OPEN frame.
         *
         * Returns null for blank input or a shape that will not parse. A caller treats
         * that as "unknown user": the shell still opens, it just cannot say who is
         * on the other end.
         */
        fun parse(raw: String): SessionIdentity? {
            if (raw.isBlank()) return null

            return try {
                json.decodeFromString(serializer(), raw)
            } catch (expected: Exception) {
                // A malformed identity is not fatal: the shell still opens, it just
                // cannot name the user. The type is named `expected` for the gate's
                // swallowed-exception rule, which is the honest reading here.
                null
            }
        }
    }
}
