package com.jossephus.chuchu.data.crypto

/**
 * Field-level encryption for the secrets kept in the Room database
 * (`password`, `keyPassphrase`, `privateKeyPem`).
 *
 * Encrypted values are self-describing: they carry [SECRET_ENVELOPE_PREFIX] so that rows
 * written before encryption existed stay distinguishable from encrypted ones. [decrypt]
 * therefore returns anything without that prefix unchanged, which means no migration pass
 * is needed: legacy rows keep working and are re-stored encrypted the next time the row is
 * written.
 *
 * Failure policy is fail-closed. A value that claims to be encrypted but cannot be
 * decrypted throws [SecretDecryptionException] instead of falling back to returning the
 * ciphertext: silently handing ciphertext to the SSH engine would surface as a bogus auth
 * failure, and silently handing back an empty password would hide data loss. Callers must
 * treat the exception as unrecoverable data loss and surface it.
 */
interface SecretCodec {
    /**
     * Encrypts [plaintext] into a `v1:<base64(iv||ciphertext||tag)>` envelope.
     *
     * An empty string is stored as an empty string (there is nothing to protect), so
     * blank credentials never require the encryption key to be available.
     */
    fun encrypt(plaintext: String): String

    /**
     * Decrypts an envelope produced by [encrypt]. Values that do not start with
     * [SECRET_ENVELOPE_PREFIX] are legacy plaintext and are returned unchanged.
     *
     * @throws SecretDecryptionException when an enveloped value cannot be decrypted.
     */
    fun decrypt(stored: String): String
}

/** Marks a value as produced by [SecretCodec.encrypt]; the `v1` is the envelope version. */
const val SECRET_ENVELOPE_PREFIX: String = "v1:"

/**
 * True when [stored] is an encrypted envelope rather than legacy plaintext.
 *
 * This is the single place the prefix is interpreted, so every reader agrees on what
 * "already encrypted" means.
 */
fun isEncryptedSecret(stored: String): Boolean = stored.startsWith(SECRET_ENVELOPE_PREFIX)

/**
 * Thrown when an encrypted value cannot be decrypted, e.g. because the AndroidKeyStore
 * entry was invalidated or the database was restored onto a device with a different key.
 *
 * Deliberately not a silent `null`/empty substitute: the secret is gone and the user has to
 * re-enter it.
 */
class SecretDecryptionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
