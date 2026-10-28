package dev.nero.tafh.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nero.tafh.data.backup.TafhBackupRepository
import dev.nero.tafh.data.crypto.FakeSecretCodec
import dev.nero.tafh.data.crypto.SECRET_ENVELOPE_PREFIX
import dev.nero.tafh.data.db.AppDatabase
import dev.nero.tafh.model.AuthMethod
import dev.nero.tafh.model.HostProfile
import dev.nero.tafh.model.SshKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves that the sensitive columns only ever hold ciphertext once a repository writes them, while
 * callers keep seeing plaintext and legacy plaintext rows keep working.
 *
 * A [FakeSecretCodec] is injected instead of the production `AndroidKeyStore` codec: the
 * JVM/Robolectric environment has no keystore, and the point under test is the repository's
 * read/write boundary, not the platform crypto provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretAtRestTest {

    private lateinit var db: AppDatabase
    private lateinit var codec: FakeSecretCodec
    private lateinit var hosts: HostRepository
    private lateinit var keys: SshKeyRepository
    private lateinit var backupRepo: TafhBackupRepository

    @Before
    fun setUp() {
        codec = FakeSecretCodec()
        db = newDatabase()
        hosts = HostRepository(db.hostProfileDao(), codec)
        keys = SshKeyRepository(db.sshKeyDao(), codec = codec)
        backupRepo = TafhBackupRepository(db, codec)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            )
            .allowMainThreadQueries()
            .build()

    @Test
    fun `host credentials are ciphertext in the database and plaintext to callers`() = runBlocking {
        val password = "p@ssw0rd-not-in-the-file"
        val passphrase = "passphrase-not-in-the-file"

        val id =
            hosts.upsert(
                hostProfile(name = "prod", password = password, keyPassphrase = passphrase)
            )

        val (storedPassword, storedPassphrase) = rawHostSecrets(id)
        assertEncryptedEnvelope(storedPassword)
        assertEquals(password, codec.decrypt(storedPassword))
        assertEncryptedEnvelope(storedPassphrase)
        assertEquals(passphrase, codec.decrypt(storedPassphrase))

        val read = hosts.getById(id)!!
        assertEquals(password, read.password)
        assertEquals(passphrase, read.keyPassphrase)

        val listed = hosts.observeAll().first().single { it.id == id }
        assertEquals(password, listed.password)
        assertEquals(passphrase, listed.keyPassphrase)

        // No column at all may leak either secret.
        val dump = rawHostDump(id)
        assertFalse(dump.contains(password))
        assertFalse(dump.contains(passphrase))
    }

    @Test
    fun `rewriting an encrypted host produces a fresh ciphertext`() = runBlocking {
        val id = hosts.upsert(hostProfile(name = "a", password = "same", keyPassphrase = "same"))
        val first = rawHostSecrets(id)

        hosts.update(hosts.getById(id)!!)
        val second = rawHostSecrets(id)

        assertNotEquals("random IV must re-randomise on every write", first, second)
        assertEquals("same", hosts.getById(id)!!.password)
        assertEquals("same", hosts.getById(id)!!.keyPassphrase)
    }

    @Test
    fun `private key pem is ciphertext in the database and plaintext to callers`() = runBlocking {
        val id = keys.insert(sshKey(name = "laptop", pem = PRIVATE_KEY_PEM))

        val storedPem = rawKeySecret(id)
        assertEncryptedEnvelope(storedPem)
        assertEquals(PRIVATE_KEY_PEM, codec.decrypt(storedPem))

        val dump = rawKeyDump(id)
        assertFalse(dump.contains("PRIVATE KEY"))
        assertFalse(dump.contains("b3BlbnNzaC1rZXktdjE"))

        assertEquals(PRIVATE_KEY_PEM, keys.getById(id)!!.privateKeyPem)
        assertEquals(PRIVATE_KEY_PEM, keys.getAll().single { it.id == id }.privateKeyPem)
        assertEquals(
            PRIVATE_KEY_PEM,
            keys.observeAll().first().single { it.id == id }.privateKeyPem,
        )
    }

    @Test
    fun `legacy plaintext host row is readable and upgraded by the next update`() = runBlocking {
        // Simulates a row written by an app version that predates encryption.
        rawHostInsert(
            name = "legacy",
            password = "legacy-password",
            keyPassphrase = "legacy-passphrase",
        )
        val id = db.hostProfileDao().getAll().single { it.name == "legacy" }.id

        val read = hosts.getById(id)!!
        assertEquals("legacy-password", read.password)
        assertEquals("legacy-passphrase", read.keyPassphrase)
        assertEquals("legacy-password", rawHostSecrets(id).first)

        hosts.update(read.copy(name = "legacy-renamed"))

        val (storedPassword, storedPassphrase) = rawHostSecrets(id)
        assertEncryptedEnvelope(storedPassword)
        assertEncryptedEnvelope(storedPassphrase)
        assertFalse(rawHostDump(id).contains("legacy-password"))
        assertFalse(rawHostDump(id).contains("legacy-passphrase"))
        assertEquals("legacy-password", hosts.getById(id)!!.password)
        assertEquals("legacy-passphrase", hosts.getById(id)!!.keyPassphrase)
    }

    @Test
    fun `legacy plaintext key row is readable and new writes are encrypted`() = runBlocking {
        rawKeyInsert(name = "legacy-key", pem = PRIVATE_KEY_PEM)
        val legacy = db.sshKeyDao().getAll().single { it.name == "legacy-key" }

        assertEquals(PRIVATE_KEY_PEM, rawKeySecret(legacy.id))
        assertEquals(PRIVATE_KEY_PEM, keys.getById(legacy.id)!!.privateKeyPem)
        assertEquals(PRIVATE_KEY_PEM, keys.getAll().single { it.id == legacy.id }.privateKeyPem)
        assertEquals(
            PRIVATE_KEY_PEM,
            keys.observeAll().first().single { it.id == legacy.id }.privateKeyPem,
        )

        // Keys have no in-place PEM update path (the DAO only inserts, renames and deletes), so a
        // legacy PEM is upgraded by re-importing the same material, which goes through the codec.
        val reWrittenId = keys.insert(keys.getById(legacy.id)!!.copy(id = 0, name = "legacy-copy"))
        assertEncryptedEnvelope(rawKeySecret(reWrittenId))
        assertEquals(PRIVATE_KEY_PEM, codec.decrypt(rawKeySecret(reWrittenId)))
        assertEquals(PRIVATE_KEY_PEM, keys.getById(reWrittenId)!!.privateKeyPem)
    }

    @Test
    fun `backup round-trip carries plaintext while both databases stay encrypted`() = runBlocking {
        val password = "backup-password"
        val passphrase = "backup-passphrase"
        val sourceKeyId = keys.insert(sshKey(name = "src-key", pem = PRIVATE_KEY_PEM))
        val sourceHostId =
            hosts.upsert(
                hostProfile(name = "src", password = password, keyPassphrase = passphrase)
                    .copy(keyId = sourceKeyId)
            )

        val payload = backupRepo.snapshotPayload()

        // The payload is what gets sealed into the (already encrypted) backup container, so it
        // must hold real secrets, never this device's ciphertext.
        assertEquals(PRIVATE_KEY_PEM, payload.keys.single { it.name == "src-key" }.privateKeyPem)
        val exportedHost = payload.hosts.single { it.name == "src" }
        assertEquals(password, exportedHost.password)
        assertEquals(passphrase, exportedHost.keyPassphrase)

        // Restore into a separate database, as if on another device sharing the fake key.
        val restoredDb = newDatabase()
        try {
            val restoredRepo = TafhBackupRepository(restoredDb, codec)
            restoredRepo.importPayload(payload)

            val restoredHosts = HostRepository(restoredDb.hostProfileDao(), codec)
            val restoredKeys = SshKeyRepository(restoredDb.sshKeyDao(), codec = codec)
            val host = restoredHosts.getAll().single { it.name == "src" }
            val key = restoredKeys.getAll().single { it.name == "src-key" }

            assertEquals(password, host.password)
            assertEquals(passphrase, host.keyPassphrase)
            assertEquals(PRIVATE_KEY_PEM, key.privateKeyPem)
            assertEquals(
                "host must reference the locally inserted key",
                key.id,
                requireNotNull(host.keyId),
            )

            // Both sides still hold only ciphertext.
            assertEncryptedEnvelope(rawHostSecrets(host.id, restoredDb).first)
            assertEncryptedEnvelope(rawKeySecret(key.id, restoredDb))
            assertFalse(rawKeyDump(key.id, restoredDb).contains("PRIVATE KEY"))
        } finally {
            restoredDb.close()
        }

        assertEncryptedEnvelope(rawHostSecrets(sourceHostId).first)
        assertEncryptedEnvelope(rawKeySecret(sourceKeyId))
    }

    @Test
    fun `empty credentials stay empty`() = runBlocking {
        val id = hosts.upsert(hostProfile(name = "blank", password = "", keyPassphrase = ""))

        val (storedPassword, storedPassphrase) = rawHostSecrets(id)
        assertEquals("", storedPassword)
        assertEquals("", storedPassphrase)
        assertEquals("", hosts.getById(id)!!.password)

        val keyId = keys.insert(sshKey(name = "nopem", pem = ""))
        assertEquals("", rawKeySecret(keyId))
        assertEquals("", keys.getById(keyId)!!.privateKeyPem)
    }

    private fun assertEncryptedEnvelope(stored: String) {
        assertTrue(
            "stored value should be an encrypted envelope but was: $stored",
            stored.startsWith(SECRET_ENVELOPE_PREFIX),
        )
    }

    // --- raw SQL access ---------------------------------------------------

    private fun rawHostSecrets(id: Long, database: AppDatabase = db): Pair<String, String> =
        database.openHelper.readableDatabase
            .query("SELECT password, keyPassphrase FROM host_profiles WHERE id = ?", arrayOf(id))
            .use { cursor ->
                check(cursor.moveToFirst()) { "host row $id missing" }
                cursor.getString(0) to cursor.getString(1)
            }

    private fun rawHostDump(id: Long): String = dumpRow("host_profiles", id)

    private fun rawKeySecret(id: Long, database: AppDatabase = db): String =
        database.openHelper.readableDatabase
            .query("SELECT privateKeyPem FROM ssh_keys WHERE id = ?", arrayOf(id))
            .use { cursor ->
                check(cursor.moveToFirst()) { "key row $id missing" }
                cursor.getString(0)
            }

    private fun rawKeyDump(id: Long, database: AppDatabase = db): String =
        dumpRow("ssh_keys", id, database)

    private fun dumpRow(table: String, id: Long, database: AppDatabase = db): String =
        database.openHelper.readableDatabase
            .query("SELECT * FROM $table WHERE id = ?", arrayOf(id))
            .use { cursor ->
                buildString {
                    while (cursor.moveToNext()) {
                        for (column in 0 until cursor.columnCount) {
                            append(cursor.getString(column).orEmpty()).append('|')
                        }
                    }
                }
            }

    private fun rawHostInsert(name: String, password: String, keyPassphrase: String) {
        db.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO host_profiles
                (name, host, port, username, password, keyId, keyPassphrase,
                 transport, authMethod, requireAuthOnConnect, postConnectCommand, multiplexer)
            VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, NULL, NULL)
            """
                .trimIndent(),
            arrayOf(name, "example.com", 22, "user", password, keyPassphrase, "SSH", "Password", 0),
        )
    }

    private fun rawKeyInsert(name: String, pem: String) {
        db.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO ssh_keys
                (name, algorithm, privateKeyPem, publicKeyOpenSsh, createdAtEpochMs)
            VALUES (?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf(name, "Ed25519", pem, "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIexample", 1L),
        )
    }

    // --- fixtures ---------------------------------------------------------

    private fun hostProfile(name: String, password: String, keyPassphrase: String) =
        HostProfile(
            name = name,
            host = "example.com",
            username = "user",
            password = password,
            keyPassphrase = keyPassphrase,
            authMethod = AuthMethod.KeyWithPassphrase,
        )

    private fun sshKey(name: String, pem: String) =
        SshKey(
            name = name,
            algorithm = "Ed25519",
            privateKeyPem = pem,
            publicKeyOpenSsh = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIexample",
            createdAtEpochMs = 1_700_000_000_000L,
        )

    private companion object {
        val PRIVATE_KEY_PEM =
            """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACBexamplebodyexamplebodyexamplebodyexamplebodyexamplebod
            -----END OPENSSH PRIVATE KEY-----
            """
                .trimIndent()
    }
}
