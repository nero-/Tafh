package com.jossephus.chuchu.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM [SecretCodec] backed by a non-exportable key in `AndroidKeyStore`.
 *
 * Every ciphertext is `v1:` + base64(12-byte random IV || ciphertext || 128-bit tag). The IV
 * is random per call (not derived), so encrypting the same password twice yields different
 * rows and no plaintext equality can be inferred from the database.
 *
 * The key does **not** require user authentication: the app lock is a UX gate, not a
 * cryptographic one, and background code paths (widget/notification triggered connects,
 * backup export) read these rows while the lock screen is up. Tying the key to biometrics
 * here would break those paths and make lockouts indistinguishable from data loss.
 *
 * Failure handling is asymmetric on purpose:
 * - encrypt recovers from a permanently invalidated or missing key by deleting and
 *   recreating the alias once, because the plaintext is in hand and the write can succeed;
 * - decrypt never recreates anything. Rotating the alias would silently make every stored
 *   secret unreadable, so instead it throws [SecretDecryptionException] with the alias name
 *   in the message. The user must re-enter the affected credentials.
 */
class KeystoreSecretCodec(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretCodec {

    /** Resolved on first use; re-resolved only after [rotateKey]. */
    @Volatile
    private var cachedKey: SecretKey? = null

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
    }

    private val random = SecureRandom()

    override fun encrypt(plaintext: String): String {
        // Nothing to protect, and blank credentials must not depend on the keystore.
        if (plaintext.isEmpty()) return ""

        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val body = iv + encryptOrRotate(iv, plaintext.toByteArray(Charsets.UTF_8))
        return SECRET_ENVELOPE_PREFIX + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): String {
        if (!isEncryptedSecret(stored)) return stored

        val body =
            try {
                Base64.decode(stored.substring(SECRET_ENVELOPE_PREFIX.length), Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                throw SecretDecryptionException("Stored secret envelope is not valid base64", e)
            }
        if (body.size <= IV_BYTES) {
            throw SecretDecryptionException("Stored secret envelope is truncated (${body.size} bytes)")
        }

        val iv = body.copyOfRange(0, IV_BYTES)
        val ciphertext = body.copyOfRange(IV_BYTES, body.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw SecretDecryptionException(decryptFailureMessage(), e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw SecretDecryptionException(decryptFailureMessage(), e)
        } catch (e: UnrecoverableKeyException) {
            throw SecretDecryptionException(decryptFailureMessage(), e)
        } catch (e: GeneralSecurityException) {
            throw SecretDecryptionException(decryptFailureMessage(), e)
        } catch (e: IOException) {
            throw SecretDecryptionException(decryptFailureMessage(), e)
        }
    }

    private fun encryptOrRotate(iv: ByteArray, plaintext: ByteArray): ByteArray =
        try {
            doEncrypt(iv, plaintext)
        } catch (_: KeyPermanentlyInvalidatedException) {
            rotateKey()
            doEncrypt(iv, plaintext)
        } catch (_: UnrecoverableKeyException) {
            rotateKey()
            doEncrypt(iv, plaintext)
        }

    private fun doEncrypt(iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        // GCM appends the authentication tag to the ciphertext, so this is ciphertext||tag.
        return cipher.doFinal(plaintext)
    }

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val key = (keyStore.getKey(keyAlias, null) as? SecretKey) ?: createKey()
            cachedKey = key
            return key
        }
    }

    private fun createKey(): SecretKey {
        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    /** Encrypt-side recovery only; see the class docs. */
    private fun rotateKey() {
        cachedKey = null
        keyStore.deleteEntry(keyAlias)
    }

    private fun decryptFailureMessage(): String =
        "Stored secret for key alias '$keyAlias' could not be decrypted. The keystore entry " +
            "was likely invalidated or the database was restored from another device; " +
            "the affected credentials must be re-entered."

    companion object {
        const val DEFAULT_KEY_ALIAS: String = "chuchu_secrets_v1"

        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256

        /** 96-bit IV: the GCM-native size, no truncation of the counter. */
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
