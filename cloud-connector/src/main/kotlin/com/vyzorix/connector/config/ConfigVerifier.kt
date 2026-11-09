package com.vyzorix.connector.config

import com.vyzorix.libv.VDocument
import com.vyzorix.libv.VNode
import com.vyzorix.libv.VParseError
import com.vyzorix.libv.VParser
import com.vyzorix.libv.VVerifier
import com.vyzorix.libv.VVerifyError
import java.security.PublicKey

/**
 * Why a config was refused. Distinct types so a caller can tell "not signed" from
 * "tampered" from "malformed" — they are different situations and only one is an
 * attack.
 */
sealed class ConfigError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The document did not parse. */
    class Malformed(
        detail: String,
        cause: Throwable? = null,
    ) : ConfigError("config is malformed: $detail", cause)

    /** The document parsed but carries no signature. */
    object Unsigned : ConfigError("config is not signed")

    /** The signature did not verify against the pinned server key. */
    class BadSignature(
        cause: Throwable? = null,
    ) : ConfigError("config signature does not match the pinned server key", cause)

    /**
     * The config mentions something secret. A config is transmitted, and the whole
     * point of the keypair model is that nothing secret is transmitted; a config
     * carrying a token would silently reintroduce exactly that. The server refuses
     * to issue one, and the device refuses to store one — the invariant is checked
     * on both sides because one bad deploy should not be able to break it.
     */
    class CarriesASecret(
        detail: String,
    ) : ConfigError("config must not carry a secret: $detail")

    /**
     * A static rule refused the config (VYZORIX_DEPLOY_CONTRACT.md §5.3, §8). The
     * [reason] is the machine-readable code the server and console key off.
     */
    class Refused(
        val reason: String,
        detail: String,
    ) : ConfigError("config refused: $reason ($detail)")
}

/**
 * The parsed, verified config: the settings the server resolved for this device.
 *
 * Settings only, by construction — [ConfigVerifier] refuses a document that
 * mentions a secret, so by the time one of these exists it has been checked.
 */
data class DeviceConfig(
    val deviceId: String,
    val organizationId: String,
    val name: String,
    val tierCap: Int,
    val streamPath: String,
    val heartbeatSeconds: Int,
    val terminalEnabled: Boolean,
    val usbEnabled: Boolean,
    val appsEnabled: Boolean,
    val version: Int,
    /** The apps the config declares, in document order. Empty when it carries none. */
    val apps: List<AppConfig>,
    /** The verified document text, stored verbatim so a re-check sees the same bytes. */
    val document: String,
)

/**
 * Verifies a delivered `vyzorix.v` and reads it.
 *
 * The signature is checked with the format's own verifier against the server's
 * public key, pinned in the app. That is the only trust anchor: the server signs
 * with a private key the device never sees, and the device holds the public half.
 * See doc/REGISTRATION_CONTRACT.md §5 and §6.
 *
 * A verification failure is a refusal, never a warning: an unverified config is an
 * attacker's config, and applying it would hand the device's policy to whoever
 * produced it.
 */
class ConfigVerifier(
    private val pinnedServerKeys: PinnedServerKeys,
    private val appConfigReader: AppConfigReader = AppConfigReader(),
    private val secretScan: ConfigSecretScan = ConfigSecretScan(),
    private val validator: ConfigValidator = ConfigValidator(),
) {
    /**
     * Convenience for a single key. Kept because a caller that holds exactly one
     * key — a test, or a build that pins one — should not have to build a keyring to
     * say so. The anchor is still a set at the point of use, so rotation is the same
     * code path.
     */
    constructor(pinnedServerKey: PublicKey) :
        this(
            PinnedServerKeys.parse(
                // Round-trips through the same parser the anchor is built with, so a
                // key handed in here is subject to the same P-256 check.
                java.util.Base64.getEncoder().encodeToString(pinnedServerKey.encoded).let { b64 ->
                    "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
                },
            ),
        )

    /**
     * Verifies [document] and returns the settings, or throws [ConfigError].
     *
     * The order matters: parse, then check for a signature, then check for a
     * secret, then verify. Checking for secrets before verifying would let an
     * unsigned document's contents influence a decision; verifying first would
     * leave a secret-carrying document one refactor away from being stored.
     */
    fun verify(document: String): DeviceConfig {
        val parsed: VDocument =
            try {
                VParser.parse(document)
            } catch (e: VParseError) {
                throw ConfigError.Malformed(e.message ?: "parse error", e)
            }

        if (parsed.signature == null) {
            throw ConfigError.Unsigned
        }

        assertNoSecrets(parsed.root)

        // Try every pinned key: during a rotation the server may sign with either,
        // and a document that verifies under one of them is authentic. A failure
        // under all of them is a bad signature.
        var lastError: Throwable? = null
        val verified =
            pinnedServerKeys.anyVerifies { key ->
                try {
                    VVerifier.verify(parsed, document, ecdsaKey = key, hmacKey = null)

                    true
                } catch (e: VVerifyError) {
                    lastError = e

                    false
                }
            }
        if (!verified) {
            throw ConfigError.BadSignature(lastError)
        }

        return read(parsed, document)
    }

    /**
     * Refuses a document that carries a secret, and names the offending key (§5.2).
     * The walk lives in [ConfigSecretScan], matching the server's `configscan`.
     */
    private fun assertNoSecrets(root: VNode) {
        secretScan.offendingKey(root)?.let { key ->
            throw ConfigError.CarriesASecret("node \"$key\" carries a secret")
        }
    }

    /**
     * Reads the settings out of the verified document.
     *
     * The document is positional (`vvv app flasher-server 3000`), so this reads by
     * path: [com.vyzorix.libv.VNode.find] walks one level per dot-separated
     * segment. Missing optional fields fall back to the fail-closed reading —
     * features off.
     */
    private fun read(
        parsed: VDocument,
        document: String,
    ): DeviceConfig {
        val root = parsed.root

        fun value(path: String): String? =
            root
                .find(path)
                .firstOrNull()
                ?.tokens
                ?.getOrNull(1)

        val deviceId = value("device") ?: throw ConfigError.Malformed("missing device")
        val orgId = value("device.org") ?: throw ConfigError.Malformed("missing org")

        val apps = appConfigReader.read(root)
        validator.validate(apps)?.let { refusal ->
            throw ConfigError.Refused(refusal.reason, refusal.detail)
        }

        return DeviceConfig(
            deviceId = deviceId,
            organizationId = orgId,
            name = value("device.name") ?: "",
            tierCap = value("device.tier")?.toIntOrNull() ?: 0,
            streamPath = value("device.cloud.stream") ?: "",
            heartbeatSeconds = parseDurationSeconds(value("device.cloud.hb")),
            terminalEnabled = value("device.features.terminal") == "true",
            usbEnabled = value("device.features.usb") == "true",
            appsEnabled = value("device.features.apps") == "true",
            version = value("version")?.toIntOrNull() ?: 0,
            apps = apps,
            document = document,
        )
    }

    /** `60s` -> 60. A value with no unit, or a non-numeric one, yields the default. */
    private fun parseDurationSeconds(raw: String?): Int {
        if (raw == null) return DEFAULT_HEARTBEAT_SECONDS
        if (!raw.endsWith("s")) return DEFAULT_HEARTBEAT_SECONDS

        return raw.dropLast(1).toIntOrNull() ?: DEFAULT_HEARTBEAT_SECONDS
    }

    companion object {
        const val DEFAULT_HEARTBEAT_SECONDS = 60
    }
}
