package com.jossephus.chuchu.data.backup

import androidx.room.withTransaction
import com.jossephus.chuchu.data.crypto.SecretCodec
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.SshKeyRepository
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.SshKey
import com.jossephus.chuchu.service.backup.ChuchuBackupCodec
import kotlinx.coroutines.flow.Flow

/**
 * Exports and restores encrypted backups.
 *
 * All reads and writes go through [SshKeyRepository] and [HostRepository] rather than the DAOs.
 * That matters because the database holds device-bound ciphertext: exporting raw rows would place
 * secrets encrypted with *this* device's `AndroidKeyStore` key into a file that is restored
 * elsewhere and then fails to decrypt, and importing through the DAOs would write plaintext
 * straight past the codec. The repositories decrypt on read (backup payloads are plaintext domain
 * models inside the already-encrypted backup container) and encrypt on write (rows stay encrypted
 * at rest).
 */
class ChuchuBackupRepository(
    private val db: AppDatabase,
    secretCodec: SecretCodec = AppDatabase.secretCodec,
) {
    private val keys = SshKeyRepository(dao = db.sshKeyDao(), codec = secretCodec)
    private val hosts = HostRepository(dao = db.hostProfileDao(), codec = secretCodec)

    fun observeKeys(): Flow<List<SshKey>> = keys.observeAll()

    fun observeHosts(): Flow<List<HostProfile>> = hosts.observeAll()

    /**
     * Reads every key and host through the repositories, i.e. with secrets decrypted. Split out
     * from [createEncryptedBackup] so the read boundary is testable without the native container.
     */
    suspend fun snapshotPayload(): BackupPayload =
        db.withTransaction {
            BackupPayload(
                keys = keys.getAll().map(BackupSshKey::fromEntity),
                hosts = hosts.getAll().map(BackupHostProfile::fromEntity),
            )
        }

    suspend fun createEncryptedBackup(passphrase: CharArray): ByteArray {
        val payload = snapshotPayload()
        return ChuchuBackupCodec.encrypt(payload, passphrase)
    }

    fun readEncryptedBackup(bytes: ByteArray, passphrase: CharArray): BackupPayload =
        ChuchuBackupCodec.decrypt(bytes, passphrase)

    suspend fun planImport(payload: BackupPayload): BackupImportPlan {
        return ChuchuBackupImportPlanner.planImport(
            payload = payload,
            existingKeys = keys.getAll(),
            existingHosts = hosts.getAll(),
        )
    }

    suspend fun importPayload(payload: BackupPayload): BackupImportResult {
        return db.withTransaction {
            val plan =
                ChuchuBackupImportPlanner.planImport(
                    payload = payload,
                    existingKeys = keys.getAll(),
                    existingHosts = hosts.getAll(),
                )
            applyImportPlan(plan)
        }
    }

    suspend fun importEncryptedBackup(bytes: ByteArray, passphrase: CharArray): BackupImportResult {
        val payload = readEncryptedBackup(bytes, passphrase)
        return importPayload(payload)
    }

    private suspend fun applyImportPlan(plan: BackupImportPlan): BackupImportResult {
        val insertedKeyIdsByActionIndex = mutableMapOf<Int, Long>()
        plan.keyActions.forEachIndexed { index, action ->
            if (action is KeyImportAction.Insert) {
                check(action.key.id == 0L) { "Imported SSH keys must not preserve exported ids" }
                val localId = keys.insertForImport(action.key)
                insertedKeyIdsByActionIndex[index] = localId
            }
        }

        var importedHosts = 0
        plan.hostActions.forEach { action ->
            check(action.host.id == 0L) { "Imported hosts must not preserve exported ids" }
            val localKeyId =
                action.keyActionIndex?.let { insertedKeyIdsByActionIndex[it] } ?: action.host.keyId
            val host = action.host.copy(keyId = localKeyId)
            hosts.insertForImport(host)
            importedHosts += 1
        }

        return BackupImportResult(
            importedKeys = plan.keyInsertCount,
            reusedKeys = plan.keyReuseCount,
            importedHosts = importedHosts,
            renamedKeys = plan.renamedKeyCount,
            renamedHosts = plan.renamedHostCount,
        )
    }
}

data class BackupImportResult(
    val importedKeys: Int,
    val reusedKeys: Int,
    val importedHosts: Int,
    val renamedKeys: Int,
    val renamedHosts: Int,
)

fun buildBackupPayload(keys: List<SshKey>, hosts: List<HostProfile>): BackupPayload =
    BackupPayload(
        keys = keys.map(BackupSshKey::fromEntity),
        hosts = hosts.map(BackupHostProfile::fromEntity),
    )
