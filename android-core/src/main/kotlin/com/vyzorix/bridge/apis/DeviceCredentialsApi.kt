package com.vyzorix.bridge.apis

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import com.vyzorix.service.VyzorixDeviceAdminReceiver
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device credentials: install and remove CA certificates and client key pairs.
 *
 * ## Why these are admin-gated but not a declared policy
 *
 * `installCaCert` and `installKeyPair` take a `ComponentName` and require that
 * component to be an **active admin**, but they correspond to no `<uses-policies>`
 * tag — there is no `install-ca-cert` in the admin XML. The gate is "is this
 * component an active admin", which is why this API checks that and reports it,
 * rather than the router gating it on a permission.
 *
 * ## What installing a CA does
 *
 * `installCaCert` adds a certificate to the **system trust store**, so every app on
 * the device trusts it. That is a serious power — the same one a corporate MDM uses
 * to trust an internal CA, and the mechanism TLS interception relies on. It is
 * reported plainly so an operator knows what they are doing; the API does not hide
 * the consequence.
 *
 * ## Input format
 *
 * A certificate is supplied as PEM (`-----BEGIN CERTIFICATE-----`) or base64 DER; a
 * private key as PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`). The bytes are decoded
 * here and handed to the platform, which does its own validation — a malformed
 * certificate is refused by `installCaCert` returning false, not by this code.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PRIVATE,
        ApiTags.OP_WRITE,
        ApiTags.PROTECTION_SIGNATURE,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object DeviceCredentialsApi : ApiHandler {

    const val NAME = "device_credentials"

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val policy =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no device policy service")

        val admin = android.content.ComponentName(context, VyzorixDeviceAdminReceiver::class.java)
        val active = policy.isAdminActive(admin)

        val result =
            JSONObject()
                .put("isActiveAdmin", active)
                .put("isDeviceOwner", policy.isDeviceOwnerApp(context.packageName))

        val action = call.args["action"].orEmpty()

        // Every action below needs an active admin; refuse early with the reason rather
        // than letting the platform throw.
        if (action.isNotEmpty() && !active) {
            return ApiOutcome.fail(
                BridgeErrorKind.FORBIDDEN,
                "this app is not an active device admin; credential installs need the admin role",
            )
        }

        return try {
            when (action) {
                "installCaCert" -> installCaCert(policy, admin, call.args["cert"].orEmpty(), result)
                "uninstallCaCert" ->
                    uninstallCaCert(policy, admin, call.args["cert"].orEmpty(), result)
                "hasCaCert" ->
                    result.put(
                        "hasCaCert",
                        policy.hasCaCertInstalled(admin, decodeCert(call.args["cert"].orEmpty())),
                    )
                "installKeyPair" -> installKeyPair(policy, admin, call.args, result)
                "removeKeyPair" ->
                    result.put("removed", policy.removeKeyPair(admin, call.args["alias"].orEmpty()))
                else -> Unit
            }

            result.put("caCerts", installedCaCerts(policy, admin))

            ApiOutcome.ok(result)
        } catch (e: Exception) {
            ApiOutcome.fail(
                BridgeErrorKind.INVALID_ARGS,
                "credential operation failed: ${e.message}",
            )
        }
    }

    /** Installs a CA certificate into the system trust store. */
    private fun installCaCert(
        policy: DevicePolicyManager,
        admin: android.content.ComponentName,
        certPem: String,
        result: JSONObject,
    ) {
        val der = decodeCert(certPem)
        result.put("installed", policy.installCaCert(admin, der)).put("action", "installCaCert")
    }

    /** Removes a CA certificate from the trust store. */
    private fun uninstallCaCert(
        policy: DevicePolicyManager,
        admin: android.content.ComponentName,
        certPem: String,
        result: JSONObject,
    ) {
        policy.uninstallCaCert(admin, decodeCert(certPem))
        result.put("uninstalled", true).put("action", "uninstallCaCert")
    }

    /** Installs a client key + certificate under [alias]. */
    private fun installKeyPair(
        policy: DevicePolicyManager,
        admin: android.content.ComponentName,
        args: Map<String, String>,
        result: JSONObject,
    ) {
        val alias = args["alias"].orEmpty()
        if (alias.isEmpty()) {
            result.put("installed", false).put("reason", "no alias supplied")
            return
        }

        val key = decodePrivateKey(args["key"].orEmpty())
        val cert = decodeCertificate(args["cert"].orEmpty())

        result
            .put("installed", policy.installKeyPair(admin, key, cert, alias))
            .put("alias", alias)
            .put("action", "installKeyPair")
    }

    /** The installed CA certificates as base64 DER, so a caller can diff them. */
    private fun installedCaCerts(
        policy: DevicePolicyManager,
        admin: android.content.ComponentName,
    ): JSONArray {
        val array = JSONArray()

        // `getInstalledCaCerts` can throw when the caller may not read them; an empty
        // list is the honest "not readable" answer.
        runCatching { policy.getInstalledCaCerts(admin) }
            .getOrDefault(emptyList())
            .forEach { array.put(Base64.getEncoder().encodeToString(it)) }

        return array
    }

    /** Decodes a PEM or base64 DER certificate to DER bytes. */
    private fun decodeCert(cert: String): ByteArray = decodeCertificate(cert).encoded

    /** Decodes a PEM or base64 DER certificate. */
    private fun decodeCertificate(cert: String): X509Certificate {
        val der = pemOrBase64(cert)

        return CertificateFactory
            .getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    /** Decodes a PKCS#8 PEM (or base64 DER) private key. */
    private fun decodePrivateKey(key: String): java.security.PrivateKey {
        val der = pemOrBase64(key)
        val spec = PKCS8EncodedKeySpec(der)

        // The key's algorithm is not carried in PKCS#8 alone for every key type; try the
        // common ones. RSA first (the usual TLS client key), then EC.
        for (algorithm in listOf("RSA", "EC")) {
            runCatching { KeyFactory.getInstance(algorithm).generatePrivate(spec) }
                .getOrNull()
                ?.let { return it }
        }

        throw IllegalArgumentException("unsupported private key (expected PKCS#8 RSA or EC)")
    }

    /**
     * The DER bytes of a PEM block, or the raw bytes of a base64 string.
     *
     * A PEM body is the base64 between the header and footer lines; a bare base64
     * string is decoded directly.
     */
    private fun pemOrBase64(value: String): ByteArray {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "empty value" }

        val body =
            if (trimmed.contains("-----BEGIN")) {
                trimmed
                    .lineSequence()
                    .filterNot { it.startsWith("-----") }
                    .joinToString("")
            } else {
                trimmed
            }

        return Base64.getMimeDecoder().decode(body)
    }
}
