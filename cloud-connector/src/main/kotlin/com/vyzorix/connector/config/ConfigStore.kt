package com.vyzorix.connector.config

/**
 * Where the verified config is kept between runs.
 *
 * The document is stored verbatim, not as a re-serialised object: it is signed, and
 * the signature covers the exact bytes. Re-encoding would produce a document whose
 * signature no longer verifies, which would look like tampering on the next read.
 *
 * Two slots are kept — the current config and the previous known-good one — because a
 * device that has just applied a config whose apps will not start must be able to put
 * back what worked (`keep_last = 2`).
 */
interface ConfigStorage {
    fun read(): String?

    fun write(document: String)

    /** The previous known-good document, retained alongside the current one. */
    fun readLastGood(): String?

    fun writeLastGood(document: String)

    /** Removes both documents. */
    fun clear()
}

/**
 * Holds the device's config: the current verified one and the previous known-good
 * one.
 *
 * The version is what makes a missed update recoverable. The server pushes the config
 * once, at claim time; a device that was offline then has none, and a device whose
 * config the user later changed has a stale one. In both cases the device asks
 * the server for the config by version and applies what comes back if the version
 * differs.
 */
class ConfigStore(
    private val storage: ConfigStorage,
    private val verifier: ConfigVerifier,
) {
    /**
     * The config held, verified on read. Null when none is stored or none verifies.
     *
     * A current document that no longer verifies (the pinned key changed, or the
     * store was tampered with) falls back to the previous known-good one rather than
     * leaving the device unconfigured.
     */
    fun current(): DeviceConfig? {
        verifyOrNull(storage.read())?.let { return it }

        val lastGood = verifyOrNull(storage.readLastGood())
        if (lastGood == null) {
            storage.clear()

            return null
        }

        storage.write(lastGood.document)

        return lastGood
    }

    /** The version held, or 0 when none. Compared against the server's to decide re-fetch. */
    fun version(): Int = current()?.version ?: 0

    /**
     * Verifies [document] and, if it is good, stores it, promoting the config it
     * replaces to the last-good slot.
     *
     * Throws [ConfigError] on a bad document and stores nothing: a refused config must
     * not replace a good one, or a single bad push would leave the device unconfigured.
     */
    fun apply(document: String): DeviceConfig {
        val config = verifier.verify(document)

        storage.read()?.let { storage.writeLastGood(it) }
        storage.write(document)

        return config
    }

    /** True when [serverVersion] differs from what is held, so the device should re-fetch. */
    fun isStale(serverVersion: Int): Boolean = serverVersion != version()

    /**
     * Restores the previous known-good config as current after a failed apply
     * ("a failed apply changes nothing").
     *
     * The applier leaves the artifacts untouched on a refusal, so the last-good
     * config still describes what is installed. Because the version then differs from
     * the server's, the next reconnect re-fetches and retries — the rollback is
     * self-healing, not a dead end.
     *
     * @return the restored config, or null when there is no usable last-good document.
     */
    fun rollback(): DeviceConfig? {
        val lastGood = verifyOrNull(storage.readLastGood()) ?: return null
        storage.write(lastGood.document)

        return lastGood
    }

    @Suppress("SwallowedException")
    private fun verifyOrNull(document: String?): DeviceConfig? {
        if (document == null) return null

        return try {
            verifier.verify(document)
        } catch (e: ConfigError) {
            null
        }
    }
}
