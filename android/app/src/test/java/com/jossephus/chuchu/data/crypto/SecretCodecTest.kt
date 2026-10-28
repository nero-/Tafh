package com.jossephus.chuchu.data.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A JVM-only [SecretCodec] with the exact same envelope as [KeystoreSecretCodec] (`v1:` +
 * base64(iv||ciphertext||tag), 12-byte random IV, 128-bit tag) but a fixed in-memory key, since
 * `AndroidKeyStore` does not exist on the JVM.
 *
 * Lives here as shared test furniture: [SecretCodecTest] pins the envelope contract and
 * `SecretAtRestTest` injects this into the repositories to prove the database only ever holds
 * ciphertext, without Robolectric ever touching the keystore.
 */
class FakeSecretCodec(private val key: ByteArray = ByteArray(32) { (it * 7 + 1).toByte() }) :
    SecretCodec {

    private val random = SecureRandom()
    private val secretKey = SecretKeySpec(key, "AES")

    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        val body = iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return SECRET_ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(body)
    }

    override fun decrypt(stored: String): String {
        if (!isEncryptedSecret(stored)) return stored
        val body =
            try {
                Base64.getDecoder().decode(stored.substring(SECRET_ENVELOPE_PREFIX.length))
            } catch (e: IllegalArgumentException) {
                throw SecretDecryptionException("test envelope is not base64", e)
            }
        if (body.size <= IV_BYTES) throw SecretDecryptionException("test envelope truncated")
        val iv = body.copyOfRange(0, IV_BYTES)
        val ciphertext = body.copyOfRange(IV_BYTES, body.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw SecretDecryptionException("test envelope failed to decrypt", e)
        }
    }

    private companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * Envelope contract for [SecretCodec]: what a stored value looks like, how legacy plaintext stays
 * readable, and that identical plaintexts never produce identical rows.
 */
class SecretCodecTest {

    private val codec: SecretCodec = FakeSecretCodec()

    @Test
    fun `empty input round-trips as the empty string`() {
        val stored = codec.encrypt("")

        assertEquals("", stored)
        assertEquals("", codec.decrypt(stored))
    }

    @Test
    fun `encrypted values carry the v1 prefix and round-trip`() {
        val plaintext = "correct horse battery staple"

        val stored = codec.encrypt(plaintext)

        assertTrue(stored.startsWith(SECRET_ENVELOPE_PREFIX))
        assertTrue(isEncryptedSecret(stored))
        assertFalse("ciphertext must not embed the plaintext", stored.contains(plaintext))
        assertEquals(plaintext, codec.decrypt(stored))
    }

    @Test
    fun `private key material round-trips through the envelope`() {
        val pem =
            """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            -----END OPENSSH PRIVATE KEY-----
            """
                .trimIndent()

        val stored = codec.encrypt(pem)

        assertTrue(stored.startsWith(SECRET_ENVELOPE_PREFIX))
        assertFalse(stored.contains("PRIVATE KEY"))
        assertEquals(pem, codec.decrypt(stored))
    }

    @Test
    fun `legacy plaintext passes through unchanged`() {
        val legacyPassword = "hunter2"
        val legacyPem = "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"

        assertEquals(legacyPassword, codec.decrypt(legacyPassword))
        assertEquals(legacyPem, codec.decrypt(legacyPem))
        assertFalse(isEncryptedSecret(legacyPassword))
    }

    @Test
    fun `two encryptions of the same input differ`() {
        val plaintext = "same-secret"

        val first = codec.encrypt(plaintext)
        val second = codec.encrypt(plaintext)

        assertNotEquals("random IV must make rows non-deterministic", first, second)
        assertEquals(plaintext, codec.decrypt(first))
        assertEquals(plaintext, codec.decrypt(second))
    }

    @Test
    fun `tampered envelope fails closed instead of returning ciphertext`() {
        val stored = codec.encrypt("s3cret").toCharArray()
        // Flip a character in the base64 body so the GCM tag no longer verifies.
        val index = stored.indexOfFirst { it.isLetterOrDigit() }.coerceAtLeast(3)
        stored[index] = if (stored[index] == 'A') 'B' else 'A'

        val tampered = String(stored)

        assertThrows(SecretDecryptionException::class.java) { codec.decrypt(tampered) }
    }
}
