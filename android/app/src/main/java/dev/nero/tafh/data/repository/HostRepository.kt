package dev.nero.tafh.data.repository

import dev.nero.tafh.data.crypto.SecretCodec
import dev.nero.tafh.data.db.AppDatabase
import dev.nero.tafh.data.db.HostProfileDao
import dev.nero.tafh.model.HostProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reads and writes [HostProfile] rows, encrypting [HostProfile.password] and
 * [HostProfile.keyPassphrase] through [codec] before they reach SQLite.
 *
 * Ciphertext never leaves this class: [observeAll] and [getById] map every row through [decode], so
 * callers always see the plaintext credentials the SSH engine needs. Legacy rows written before
 * encryption existed (or imported by a pre-encryption backup) are returned as-is by the codec and
 * are re-stored encrypted the next time the row is written.
 */
class HostRepository(
    private val dao: HostProfileDao,
    private val codec: SecretCodec = AppDatabase.secretCodec,
) {
    fun observeAll(): Flow<List<HostProfile>> = dao.observeAll().map { rows -> rows.map(::decode) }

    fun observeKeyUsageCounts(): Flow<Map<Long, Int>> =
        dao.observeKeyUsage().map { rows -> rows.associate { it.keyId to it.count } }

    suspend fun getById(id: Long): HostProfile? = dao.getById(id)?.let(::decode)

    suspend fun getAll(): List<HostProfile> = dao.getAll().map(::decode)

    suspend fun upsert(profile: HostProfile): Long = dao.insert(encode(profile))

    /**
     * Raw insert used by backup restore. Kept separate from [upsert] because the DAO insert behind
     * it aborts on conflict instead of replacing, which is what keeps a restore from silently
     * overwriting an existing host.
     */
    suspend fun insertForImport(profile: HostProfile): Long = dao.insertForImport(encode(profile))

    suspend fun update(profile: HostProfile) = dao.update(encode(profile))

    suspend fun delete(profile: HostProfile) = dao.delete(profile)

    suspend fun clearKeyReference(keyId: Long) = dao.clearKeyReference(keyId)

    /**
     * Empty secrets are stored as empty strings by the codec, so a profile with no password and no
     * passphrase is written without touching the keystore at all.
     */
    private fun encode(profile: HostProfile): HostProfile =
        profile.copy(
            password = codec.encrypt(profile.password),
            keyPassphrase = codec.encrypt(profile.keyPassphrase),
        )

    private fun decode(profile: HostProfile): HostProfile =
        profile.copy(
            password = codec.decrypt(profile.password),
            keyPassphrase = codec.decrypt(profile.keyPassphrase),
        )
}
