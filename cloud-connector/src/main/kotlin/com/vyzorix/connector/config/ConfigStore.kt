package com.vyzorix.connector.config

/**
 * Where the verified config is kept between runs.
 *
 * The document is stored verbatim, not as a re-serialised object: it is signed,
 * and the signature covers the exact bytes. Re-encoding would produce a document
 * whose signature no longer verifies, which would look like tampering on the next
 * read. Storage is a small key/value surface so the Android implementation
 * (DataStore) and the test implementation (a map) behave identically.
 */
interface ConfigStorage {
    fun read(): String?

    fun write(document: String)

    fun clear()
}

/**
 * Holds the device's config: the last verified one, and whether it is current.
 *
 * The version is what makes a missed update recoverable. The server pushes the
 * config once, at claim time; a device that was offline then has none, and a
 * device whose config the operator later changed has a stale one. In both cases
 * the device asks the server for the config by version and applies what comes
 * back if the version differs. See doc/REGISTRATION_CONTRACT.md §7.
 */
class ConfigStore(
    private val storage: ConfigStorage,
    private val verifier: ConfigVerifier,
) {
    /** The config held, verified on read. Null when none is stored or it no longer verifies. */
    @Suppress("SwallowedException")
    fun current(): DeviceConfig? {
        val document = storage.read() ?: return null

        return try {
            verifier.verify(document)
        } catch (e: ConfigError) {
            // A stored document that no longer verifies means the pinned key changed or
            // the store was tampered with. Either way it must not be applied, and it is
            // dropped so the device re-fetches rather than wedging on it.
            storage.clear()

            null
        }
    }

    /** The version held, or 0 when none. Compared against the server's to decide re-fetch. */
    fun version(): Int = current()?.version ?: 0

    /**
     * Verifies [document] and, if it is good, stores it.
     *
     * Throws [ConfigError] on a bad document and stores nothing: a refused config
     * must not replace a good one, or a single bad push would leave the device
     * unconfigured.
     */
    fun apply(document: String): DeviceConfig {
        val config = verifier.verify(document)
        storage.write(document)

        return config
    }

    /** True when [serverVersion] differs from what is held, so the device should re-fetch. */
    fun isStale(serverVersion: Int): Boolean = serverVersion != version()
}
