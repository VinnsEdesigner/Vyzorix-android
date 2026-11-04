package com.vyzorix.connector.identity

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * The device's signing identity, expressed as the two operations the rest of the
 * module needs: the public half in PEM, and a signature over a string.
 *
 * The rest of the module depends on this interface, not on the Keystore, so the
 * canonical-string and handshake logic — the part that can silently disagree with
 * the server — is exercised on the JVM with a plain keypair, no device and no
 * emulator. [KeystoreDeviceIdentity] is the production implementation; tests use
 * [SoftwareDeviceIdentity].
 *
 * This is the device's whole authority. It signs the enroll request, the connect
 * handshake and every request; the server holds only the public half, and nothing
 * secret is ever transmitted. See doc/REGISTRATION_CONTRACT.md §5.
 */
interface DeviceSigner {
    /** The public half as PEM SubjectPublicKeyInfo, the shape enroll sends. */
    fun publicKeyPem(): String

    /**
     * Signs [message] and returns base64 ASN.1 DER, the shape the server verifies.
     *
     * [urlSafe] selects base64url for the connect handshake, whose signature
     * travels as a query parameter where standard base64's `+` would be read as a
     * space.
     */
    fun sign(
        message: String,
        urlSafe: Boolean = false,
    ): String
}

/** Shared signing so the Keystore and software implementations cannot diverge. */
internal object Signing {
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** PEM wraps base64 at 64 columns, the convention `pem.EncodeToMemory` uses. */
    private const val PEM_LINE_WIDTH = 64

    fun sign(
        privateKey: PrivateKey,
        message: String,
        urlSafe: Boolean,
    ): String {
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(message.toByteArray(Charsets.UTF_8))

        return encode(signer.sign(), urlSafe)
    }

    fun encode(
        der: ByteArray,
        urlSafe: Boolean,
    ): String =
        if (urlSafe) {
            Base64.getUrlEncoder().withoutPadding().encodeToString(der)
        } else {
            Base64.getEncoder().encodeToString(der)
        }

    /** PEM SubjectPublicKeyInfo, 64-column wrapped, as `x509.MarshalPKIXPublicKey` produces. */
    fun pemOf(publicKey: PublicKey): String =
        buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            append(
                Base64
                    .getEncoder()
                    .encodeToString(publicKey.encoded)
                    .chunked(PEM_LINE_WIDTH)
                    .joinToString("\n"),
            )
            append("\n-----END PUBLIC KEY-----\n")
        }
}
