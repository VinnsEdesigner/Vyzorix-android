package com.vyzorix.connector.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.vyzorix.connector.identity.Signing
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * The device's secret-decryption key: an RSA-3072 keypair in the Android Keystore
 * with `PURPOSE_DECRYPT`, generated on first run and never exported
 * (VYZORIX_DEPLOY_CONTRACT.md §9b.3).
 *
 * It is deliberately separate from the identity key. The identity key signs and is
 * generated `PURPOSE_SIGN` only; its public half is handed to the container, so it
 * is not a key a secret's confidentiality should rest on. This key is never exposed
 * over the bridge — there is no `SecretApi` — so the container cannot decrypt.
 *
 * ## The OAEP parameters are fixed by the Keystore, not chosen
 *
 * The Android Keystore accepts only `MGF1-SHA1`, so the envelope is `RSA-OAEP-SHA1`.
 * The obvious "harden it to SHA-256" edit is refused by the Keystore at generate
 * time, which is why the parameters are pinned here with the reason.
 */
class SecretKey(
    private val keyStore: KeyStore,
    private val alias: String = DEFAULT_ALIAS,
) : SecretDecryptor {
    private val keyPair: KeyPair by lazy { loadOrCreate() }

    /** The public key as PEM, for enroll. Its private half never leaves the device. */
    fun publicKeyPem(): String = Signing.pemOf(keyPair.public)

    /**
     * Decrypts a base64 RSA-OAEP-SHA1 ciphertext, or null when it will not open.
     *
     * A null return is a refusal, not an error to retry: a ciphertext wrapped to a
     * different key never opens, and the caller reports `secret_undecryptable`
     * rather than starting an app with the variable absent (§9b.5).
     */
    @Suppress("SwallowedException")
    override fun decrypt(ciphertextBase64: String): ByteArray? =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyPair.private, OAEP)
            cipher.doFinal(
                java.util.Base64
                    .getDecoder()
                    .decode(ciphertextBase64),
            )
        } catch (e: Exception) {
            null
        }

    private fun loadOrCreate(): KeyPair {
        keyStore.load(null)

        (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { entry ->
            return KeyPair(entry.certificate.publicKey, entry.privateKey)
        }

        KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            .apply {
                initialize(
                    KeyGenParameterSpec
                        .Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                        .setAlgorithmParameterSpec(
                            RSAKeyGenParameterSpec(KEY_SIZE, RSAKeyGenParameterSpec.F4),
                        ).setDigests(KeyProperties.DIGEST_SHA1)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                        .build(),
                )
            }.generateKeyPair()

        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry

        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    companion object {
        const val DEFAULT_ALIAS = "vyzorix.secret"

        /** The Android Keystore provider name. */
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** RSA-3072, the size the contract fixes for this key. */
        const val KEY_SIZE = 3072

        /**
         * The one transformation the Keystore supports for this key. The MGF1 digest
         * must be SHA-1 — the Keystore rejects SHA-256 outright.
         */
        const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"

        val OAEP: OAEPParameterSpec =
            OAEPParameterSpec(
                "SHA-1",
                "MGF1",
                MGF1ParameterSpec.SHA1,
                PSource.PSpecified.DEFAULT,
            )
    }
}
