package com.sebrain.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedBackupCodecTest {
    private val metadata = BackupMetadata(3, "2026-11-11T22:17:00Z", 42)
    private val passphrase = "correct horse battery staple".toCharArray()
    private val payload = "encrypted vault export".toByteArray()

    @Test
    fun roundTripPreservesPayloadAndMetadata() {
        val blob = EncryptedBackupCodec.encrypt(payload, passphrase, metadata)
        val restored = EncryptedBackupCodec.decrypt(blob, passphrase)

        assertArrayEquals(payload, restored.payload)
        assertEquals(metadata, restored.metadata)
        assertFalse(blob.toString(Charsets.UTF_8).contains("encrypted vault export"))
    }

    @Test
    fun wrongPassphraseFailsAuthentication() {
        val blob = EncryptedBackupCodec.encrypt(payload, passphrase, metadata)

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackupCodec.decrypt(blob, "wrong passphrase".toCharArray())
        }
    }

    @Test
    fun tamperingWithHeaderOrCiphertextFails() {
        val blob = EncryptedBackupCodec.encrypt(payload, passphrase, metadata)
        blob[0] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackupCodec.decrypt(blob, passphrase)
        }

        val second = EncryptedBackupCodec.encrypt(payload, passphrase, metadata)
        second[second.lastIndex] = (second[second.lastIndex].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackupCodec.decrypt(second, passphrase)
        }
    }
}
