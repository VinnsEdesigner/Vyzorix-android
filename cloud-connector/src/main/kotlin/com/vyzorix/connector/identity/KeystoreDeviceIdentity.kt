package com.vyzorix.connector.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/**
 * The production [DeviceSigner]: an EC P-256 keypair in the Android Keystore,
 * generated once on first run and never exported.
 *
 * The private key is non-exportable by construction — the Keystore never returns
 * it and [sign] is the only way to use it. A reinstall destroys the Keystore
 * entry, which is correct: a new install is a new device identity, and the server
 * treats an unknown public key as an unknown device.
 *
 * The Keystore work is confined to [loadOrCreate]; everything else is the shared
 * [Signing] logic, so the canonical-string behaviour is identical to the software
 * signer the tests use. That split is deliberate: the part that can silently
 * disagree with the server is testable on the JVM.
 *
 * `KeyGenParameterSpec` generates a self-signed certificate alongside the key, so
 * no X.509 assembly is needed. The certificate is never verified — the public half
 * is what enroll sends — it exists only because the Keystore requires a chain for
 * a private-key entry.
 */
class KeystoreDeviceIdentity(
    private val keyStore: KeyStore,
    private val alias: String = DEFAULT_ALIAS,
) : DeviceSigner {
    private val keyPair: KeyPair by lazy { loadOrCreate() }

    override fun publicKeyPem(): String = Signing.pemOf(keyPair.public)

    override fun sign(
        message: String,
        urlSafe: Boolean,
    ): String = Signing.sign(keyPair.private, message, urlSafe)

    private fun loadOrCreate(): KeyPair {
        keyStore.load(null)

        (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { entry ->
            return KeyPair(entry.certificate.publicKey, entry.privateKey)
        }

        KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .apply {
                initialize(
                    KeyGenParameterSpec
                        .Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()

        // Read the key back rather than returning the generator's pair: the Keystore
        // is the source of truth, and a fresh read proves the entry is usable.
        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry

        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    companion object {
        const val DEFAULT_ALIAS = "vyzorix-device-identity"

        /** The Android Keystore provider name. */
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** P-256, the curve the server requires (`DecodePublicKey` rejects others). */
        const val CURVE = "secp256r1"
    }
}
