package com.vyzorix.connector.config

import com.vyzorix.libv.VVerifier
import com.vyzorix.libv.VVerifyError
import java.security.PublicKey

/**
 * The set of server public keys this build trusts — the device's only trust anchor.
 *
 * ## Why this type exists
 *
 * The anchor used to be a single PEM string passed around and parsed at the point
 * of use, which left two holes a security review has to close:
 *
 *  1. **No rotation.** A single key means rotating the server's signing key bricks
 *     every deployed device at once, because the only way to trust a new key is a
 *     new APK. A sideloaded app has no store to push one through.
 *  2. **Fail-open on an empty anchor.** An empty string parsed to "no key", and
 *     `CloudConnectionFactory` answered that by returning a *bridge-only* connection
 *     — the device simply ran without the cloud and without a trust anchor, which
 *     looks like a working device that never registers.
 *
 * A trust anchor is a set of keys, not a key: old and new overlap during a
 * rotation, and a build with no key at all is refused rather than degraded.
 *
 * ## What it is not
 *
 * There is no key *fetch*. The anchor is compiled in. A trust anchor that is
 * fetched over the same channel it is meant to authenticate authenticates nothing —
 * whoever can serve the fetch can serve the key.
 */
class PinnedServerKeys private constructor(
    /** The trusted keys, newest first. At least one; see [parse]. */
    private val keys: List<PublicKey>,
) {
    /**
     * Returns true when [verify] accepts the signature under *any* pinned key.
     *
     * Verification is tried against each key in turn, and a failure under one key is
     * not fatal while another remains — that is the whole point of an overlap. A
     * signature that verifies under no key is refused.
     *
     * This is the only way to use the anchor, on purpose: an accessor that handed out
     * the keys would let a caller verify against just the first one and quietly
     * reintroduce the single-key behaviour this type exists to remove.
     */
    fun anyVerifies(verify: (PublicKey) -> Boolean): Boolean = keys.any(verify)

    companion object {
        /**
         * Parses the pinned key material.
         *
         * [pem] may hold more than one PEM block: every `-----BEGIN PUBLIC KEY-----`
         * block is a trusted key. That is the rotation format — a build that trusts
         * `[new, old]` keeps working while the server switches to `new`, and a later
         * build drops `old`.
         *
         * Throws [PinnedKeyError] when nothing parses. An empty or unusable anchor is
         * a configuration fault, not a state to run in: a device with no anchor cannot
         * verify anything the server sends, so it must not present itself as
         * configured. The caller decides how loud to be; the connector refuses to
         * build a connection.
         */
        fun parse(pem: String): PinnedServerKeys {
            val blocks = splitPemBlocks(pem)
            if (blocks.isEmpty()) {
                throw PinnedKeyError("no pinned server key: the device has no trust anchor")
            }

            val parsed =
                blocks.map { block ->
                    try {
                        VVerifier.parsePublicKey(block)
                    } catch (e: VVerifyError) {
                        throw PinnedKeyError("pinned server key is unusable: ${e.message}", e)
                    }
                }

            return PinnedServerKeys(parsed)
        }

        /**
         * Splits a string into complete PEM public-key blocks.
         *
         * Handles the two shapes the anchor arrives in: a single key (the common
         * case) and several concatenated blocks (a rotation). Anything that is not a
         * complete block is ignored rather than half-parsed — a truncated block would
         * otherwise surface as a parse error naming the wrong problem.
         */
        private fun splitPemBlocks(pem: String): List<String> {
            val begin = "-----BEGIN PUBLIC KEY-----"
            val end = "-----END PUBLIC KEY-----"

            val blocks = mutableListOf<String>()
            var cursor = 0
            while (true) {
                val start = pem.indexOf(begin, cursor)
                if (start < 0) break
                val stop = pem.indexOf(end, start)
                if (stop < 0) break
                blocks += pem.substring(start, stop + end.length)
                cursor = stop + end.length
            }

            return blocks
        }
    }
}

/** The pinned anchor is missing or unusable. The connection must not be built. */
class PinnedKeyError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
