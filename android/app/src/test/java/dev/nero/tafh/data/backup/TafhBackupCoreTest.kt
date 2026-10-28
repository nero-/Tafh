package dev.nero.tafh.data.backup

import dev.nero.tafh.model.AuthMethod
import dev.nero.tafh.model.HostProfile
import dev.nero.tafh.model.MultiplexerType
import dev.nero.tafh.model.SshKey
import dev.nero.tafh.model.Transport
import dev.nero.tafh.service.backup.NativeBackupBridge
import dev.nero.tafh.service.backup.TafhBackupCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TafhBackupCoreTest {
    @Test
    fun encryptedBackupRoundTripPreservesAllCurrentFields() {
        assumeNativeBackupAvailable()
        val payload = samplePayload()

        val encrypted = TafhBackupCodec.encrypt(payload, "correct horse".toCharArray())
        val decrypted = TafhBackupCodec.decrypt(encrypted, "correct horse".toCharArray())

        assertEquals(payload, decrypted)
        assertEquals("echo hello", decrypted.hosts.single().postConnectCommand)
        assertEquals(MultiplexerType.Tmux, decrypted.hosts.single().multiplexer)
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("PRIVATE KEY"))
    }

    @Test(expected = InvalidBackupPassphraseException::class)
    fun wrongPassphraseFailsWithoutPayload() {
        assumeNativeBackupAvailable()
        val encrypted = TafhBackupCodec.encrypt(samplePayload(), "right".toCharArray())

        TafhBackupCodec.decrypt(encrypted, "wrong".toCharArray())
    }

    @Test
    fun encryptedBackupsUseFreshSaltAndIv() {
        assumeNativeBackupAvailable()
        val payload = samplePayload()

        val first = TafhBackupCodec.encrypt(payload, "same passphrase".toCharArray())
        val second = TafhBackupCodec.encrypt(payload, "same passphrase".toCharArray())

        assertFalse(first.contentEquals(second))
    }

    @Test(expected = InvalidBackupPassphraseException::class)
    fun encryptedBackupRejectsCiphertextTampering() {
        assumeNativeBackupAvailable()
        val encrypted = TafhBackupCodec.encrypt(samplePayload(), "right".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted[encrypted.lastIndex].toInt() xor 0x01).toByte()

        TafhBackupCodec.decrypt(encrypted, "right".toCharArray())
    }

    @Test(expected = BackupFormatException::class)
    fun encryptedBackupRejectsOversizedCiphertextBeforeAllocation() {
        assumeNativeBackupAvailable()
        val encrypted = TafhBackupCodec.encrypt(samplePayload(), "right".toCharArray())
        writeIntAt(
            encrypted,
            offset = ciphertextSizeOffset(encrypted),
            value = TafhBackupCodec.MAX_BACKUP_SIZE_BYTES + 1,
        )

        TafhBackupCodec.decrypt(encrypted, "right".toCharArray())
    }

    @Test(expected = BackupFormatException::class)
    fun payloadEncodeRejectsOversizedStrings() {
        val oversizedName = "x".repeat((4 * 1024 * 1024) + 1)

        TafhBackupCodec.encodePayload(
            samplePayload()
                .copy(keys = listOf(BackupSshKey.fromEntity(sampleKey(name = oversizedName))))
        )
    }

    @Test
    fun importPlanReusesIdenticalKeysAndRenamesConflictingKeys() {
        val existing = sampleKey(id = 50L, name = "main")
        val conflicting = sampleKey(id = 1L, name = "main", privateKeyPem = "DIFFERENT")
        val identical = BackupSshKey.fromEntity(existing.copy(id = 2L))
        val payload =
            BackupPayload(
                keys = listOf(identical, BackupSshKey.fromEntity(conflicting)),
                hosts = emptyList(),
            )

        val plan =
            TafhBackupImportPlanner.planImport(
                payload = payload,
                existingKeys = listOf(existing),
                existingHosts = emptyList(),
            )

        assertEquals(1, plan.keyReuseCount)
        assertEquals(1, plan.keyInsertCount)
        val insert = plan.keyActions.filterIsInstance<KeyImportAction.Insert>().single()
        assertEquals("main-2", insert.key.name)
        assertEquals(0L, insert.key.id)
        assertTrue(insert.renamed)
    }

    @Test
    fun importPlanNeverPreservesExportedPrimaryKeys() {
        val payload =
            BackupPayload(
                keys = listOf(BackupSshKey.fromEntity(sampleKey(id = 10L, name = "new"))),
                hosts = listOf(BackupHostProfile.fromEntity(sampleHost(id = 10L, keyId = 10L))),
            )

        val plan =
            TafhBackupImportPlanner.planImport(
                payload = payload,
                existingKeys = listOf(sampleKey(id = 10L, name = "local")),
                existingHosts = listOf(sampleHost(id = 10L, name = "local-host", keyId = null)),
            )

        val insertKey = plan.keyActions.filterIsInstance<KeyImportAction.Insert>().single()
        val insertHost = plan.hostActions.single()
        assertEquals(0L, insertKey.key.id)
        assertEquals(0L, insertHost.host.id)
        assertEquals("new", insertKey.key.name)
        assertEquals("server", insertHost.host.name)
    }

    @Test
    fun importPlanRemapsHostsToReusedLocalKeyIds() {
        val existing = sampleKey(id = 77L, name = "main")
        val payload =
            BackupPayload(
                keys = listOf(BackupSshKey.fromEntity(existing.copy(id = 1L))),
                hosts = listOf(BackupHostProfile.fromEntity(sampleHost(id = 2L, keyId = 1L))),
            )

        val plan =
            TafhBackupImportPlanner.planImport(
                payload = payload,
                existingKeys = listOf(existing),
                existingHosts = emptyList(),
            )

        assertEquals(77L, plan.hostActions.single().host.keyId)
        assertNull(plan.hostActions.single().keyActionIndex)
    }

    @Test
    fun importPlanTracksInsertedKeyForDeferredHostRemap() {
        val payload =
            BackupPayload(
                keys = listOf(BackupSshKey.fromEntity(sampleKey(id = 1L, name = "main"))),
                hosts = listOf(BackupHostProfile.fromEntity(sampleHost(id = 2L, keyId = 1L))),
            )

        val plan =
            TafhBackupImportPlanner.planImport(
                payload = payload,
                existingKeys = emptyList(),
                existingHosts = emptyList(),
            )

        assertNull(plan.hostActions.single().host.keyId)
        assertEquals(0, plan.hostActions.single().keyActionIndex)
    }

    @Test(expected = BackupFormatException::class)
    fun importPlanRejectsMissingKeyReferences() {
        val payload =
            BackupPayload(
                keys = emptyList(),
                hosts = listOf(BackupHostProfile.fromEntity(sampleHost(id = 2L, keyId = 1L))),
            )

        TafhBackupImportPlanner.planImport(
            payload = payload,
            existingKeys = emptyList(),
            existingHosts = emptyList(),
        )
    }

    @Test(expected = BackupFormatException::class)
    fun payloadDecodeRejectsUnknownEnums() {
        val payload =
            samplePayload()
                .copy(
                    hosts =
                        listOf(
                            BackupHostProfile.fromEntity(sampleHost(authMethod = AuthMethod.Key))
                        )
                )
        val encoded = TafhBackupCodec.encodePayload(payload)
        replaceLastAscii(encoded, "Key", "Bog")

        TafhBackupCodec.decodePayload(encoded)
    }

    @Test
    fun payloadV2PreservesMultiplexerWithStableLowercaseId() {
        val encoded = TafhBackupCodec.encodePayload(samplePayload())
        val encodedText = String(encoded, Charsets.ISO_8859_1)
        val decoded = TafhBackupCodec.decodePayload(encoded)

        assertTrue(encodedText.contains("tmux"))
        assertFalse(encodedText.contains("Tmux"))
        assertEquals(MultiplexerType.Tmux, decoded.hosts.single().multiplexer)
    }

    @Test
    fun payloadV2ReadsLegacyEnumMultiplexerName() {
        val encoded = TafhBackupCodec.encodePayload(samplePayload())
        replaceLastAscii(encoded, "tmux", "Tmux")

        val decoded = TafhBackupCodec.decodePayload(encoded)

        assertEquals(MultiplexerType.Tmux, decoded.hosts.single().multiplexer)
    }

    @Test
    fun payloadV1DefaultsMultiplexerToNull() {
        val v2 = TafhBackupCodec.encodePayload(samplePayload())
        writeIntAt(v2, offset = Int.SIZE_BYTES, value = 1)
        val v1 = v2.copyOf(v2.size - encodedNullableStringSize(MultiplexerType.Tmux.id))

        val decoded = TafhBackupCodec.decodePayload(v1)

        assertNull(decoded.hosts.single().multiplexer)
        assertEquals("echo hello", decoded.hosts.single().postConnectCommand)
    }

    @Test(expected = BackupFormatException::class)
    fun payloadDecodeRejectsMalformedBytes() {
        TafhBackupCodec.decodePayload(byteArrayOf(1, 2, 3))
    }

    private fun assumeNativeBackupAvailable() {
        assumeTrue("native backup bridge unavailable in this JVM", NativeBackupBridge().isLoaded())
    }

    private fun samplePayload(): BackupPayload =
        BackupPayload(
            keys = listOf(BackupSshKey.fromEntity(sampleKey())),
            hosts = listOf(BackupHostProfile.fromEntity(sampleHost())),
        )

    private fun sampleKey(
        id: Long = 1L,
        name: String = "main",
        privateKeyPem: String = "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----",
    ): SshKey =
        SshKey(
            id = id,
            name = name,
            algorithm = "ED25519",
            privateKeyPem = privateKeyPem,
            publicKeyOpenSsh = "ssh-ed25519 AAAA test",
            createdAtEpochMs = 1234L,
        )

    private fun sampleHost(
        id: Long = 2L,
        name: String = "server",
        keyId: Long? = 1L,
        authMethod: AuthMethod = AuthMethod.KeyWithPassphrase,
        transport: Transport = Transport.Mosh,
    ): HostProfile =
        HostProfile(
            id = id,
            name = name,
            host = "example.com",
            port = 2222,
            username = "salem",
            password = "saved-password",
            keyId = keyId,
            keyPassphrase = "key-passphrase",
            transport = transport,
            authMethod = authMethod,
            requireAuthOnConnect = true,
            postConnectCommand = "echo hello",
            multiplexer = MultiplexerType.Tmux,
        )

    private fun encodedNullableStringSize(value: String): Int =
        1 + Int.SIZE_BYTES + value.toByteArray(Charsets.UTF_8).size

    private fun ciphertextSizeOffset(bytes: ByteArray): Int {
        var offset = Int.SIZE_BYTES * 5
        val saltSize = readIntAt(bytes, offset)
        offset += Int.SIZE_BYTES + saltSize
        val ivSize = readIntAt(bytes, offset)
        offset += Int.SIZE_BYTES + ivSize
        return offset
    }

    private fun readIntAt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private fun writeIntAt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun replaceLastAscii(bytes: ByteArray, oldValue: String, newValue: String) {
        val oldBytes = oldValue.toByteArray(Charsets.UTF_8)
        val newBytes = newValue.toByteArray(Charsets.UTF_8)
        require(oldBytes.size == newBytes.size)
        var matchIndex = -1
        for (index in 0..bytes.size - oldBytes.size) {
            if (oldBytes.indices.all { offset -> bytes[index + offset] == oldBytes[offset] }) {
                matchIndex = index
            }
        }
        require(matchIndex >= 0)
        newBytes.copyInto(bytes, destinationOffset = matchIndex)
    }
}
