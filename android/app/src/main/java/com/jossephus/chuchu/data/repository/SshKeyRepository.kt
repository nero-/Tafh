package com.jossephus.chuchu.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.jossephus.chuchu.data.crypto.SecretCodec
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.db.SshKeyDao
import com.jossephus.chuchu.model.SshKey
import com.jossephus.chuchu.service.ssh.Ed25519KeyGenerator
import com.jossephus.chuchu.service.ssh.SshKeyImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Reads and writes [SshKey] rows, encrypting [SshKey.privateKeyPem] through [codec] before it
 * reaches SQLite. Public keys, names and algorithms stay plaintext so they remain queryable and
 * displayable without the keystore.
 *
 * Ciphertext never leaves this class: every read maps rows through [decode]. Legacy rows (plaintext
 * PEMs from before encryption, or restored from a pre-encryption backup) decrypt as a no-op and are
 * re-stored encrypted the next time that row is written.
 */
class SshKeyRepository(
    private val dao: SshKeyDao,
    private val keyGenerator: Ed25519KeyGenerator = Ed25519KeyGenerator(),
    private val keyImporter: SshKeyImporter = SshKeyImporter(),
    private val codec: SecretCodec = AppDatabase.secretCodec,
) {
    fun observeAll(): Flow<List<SshKey>> = dao.observeAll().map { rows -> rows.map(::decode) }

    suspend fun getById(id: Long): SshKey? = dao.getById(id)?.let(::decode)

    suspend fun getAll(): List<SshKey> = dao.getAll().map(::decode)

    suspend fun insert(key: SshKey): Long = dao.insert(encode(key))

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun generate(nameHint: String, passphrase: String = ""): KeyGenerationResult {
        val base = nameHint.trim().ifBlank { "android-ed25519" }
        var name = uniquify(base, dao.getAll().mapTo(HashSet()) { it.name })
        while (true) {
            val key =
                try {
                    withContext(Dispatchers.Default) { keyGenerator.generate(name, passphrase) }
                } catch (_: IllegalStateException) {
                    return KeyGenerationResult.Failed
                }
            try {
                return KeyGenerationResult.Success(key.copy(id = dao.insert(encode(key))))
            } catch (_: SQLiteConstraintException) {
                name = uniquify(base, dao.getAll().mapTo(HashSet()) { it.name })
            }
        }
    }

    suspend fun import(nameHint: String, privateKeyPem: String): ImportResult {
        val pem = privateKeyPem.trim()
        if (pem.isBlank()) return ImportResult.Blank
        if (!pem.contains("PRIVATE KEY")) return ImportResult.NotAPrivateKey

        val derived = withContext(Dispatchers.Default) { keyImporter.derivePublicKey(pem) }
        val base = nameHint.trim().ifBlank { "imported-key" }
        var name = uniquify(base, dao.getAll().mapTo(HashSet()) { it.name })
        while (true) {
            val key =
                SshKey(
                    name = name,
                    algorithm = derived?.algorithm?.let(::normalizeAlgorithm) ?: "imported",
                    privateKeyPem = pem,
                    publicKeyOpenSsh = derived?.publicKeyOpenSsh ?: "",
                    createdAtEpochMs = System.currentTimeMillis(),
                )
            try {
                return ImportResult.Success(
                    key.copy(id = dao.insert(encode(key))),
                    publicKeyDerived = derived != null,
                )
            } catch (_: SQLiteConstraintException) {
                name = uniquify(base, dao.getAll().mapTo(HashSet()) { it.name })
            }
        }
    }

    suspend fun rename(id: Long, newName: String): RenameResult {
        val name = newName.trim()
        if (name.isBlank()) return RenameResult.Blank
        if (dao.nameExists(name, excludeId = id)) return RenameResult.NameTaken
        return try {
            dao.rename(id, name)
            RenameResult.Success
        } catch (_: SQLiteConstraintException) {
            RenameResult.NameTaken
        }
    }

    private fun encode(key: SshKey): SshKey =
        key.copy(privateKeyPem = codec.encrypt(key.privateKeyPem))

    private fun decode(key: SshKey): SshKey =
        key.copy(privateKeyPem = codec.decrypt(key.privateKeyPem))

    private fun normalizeAlgorithm(keytype: String): String =
        when {
            keytype == "ssh-ed25519" -> "Ed25519"
            keytype == "ssh-rsa" -> "RSA"
            keytype == "ssh-dss" -> "DSA"
            keytype.startsWith("ecdsa-") -> "ECDSA"
            else -> keytype
        }

    private fun uniquify(base: String, existingNames: Set<String>): String {
        if (base !in existingNames) return base
        var index = 2
        var candidate = "$base-$index"
        while (candidate in existingNames) {
            index += 1
            candidate = "$base-$index"
        }
        return candidate
    }
}

enum class RenameResult {
    Success,
    Blank,
    NameTaken,
}

sealed interface ImportResult {
    data class Success(val key: SshKey, val publicKeyDerived: Boolean) : ImportResult

    data object Blank : ImportResult

    data object NotAPrivateKey : ImportResult
}

sealed interface KeyGenerationResult {
    data class Success(val key: SshKey) : KeyGenerationResult

    data object Failed : KeyGenerationResult
}
