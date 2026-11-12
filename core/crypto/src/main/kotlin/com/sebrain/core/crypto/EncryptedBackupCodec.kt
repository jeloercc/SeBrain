package com.sebrain.core.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupMetadata(
    val schemaVersion: Int,
    val createdAt: String,
    val recordCount: Long,
)

data class DecryptedBackup(
    val metadata: BackupMetadata,
    val payload: ByteArray,
)

/**
 * Encrypts a backup payload with a user-supplied recovery passphrase.
 *
 * The payload is expected to be an already-consistent vault export. This codec does
 * not access the vault DEK and cannot replace or overwrite a vault.
 */
object EncryptedBackupCodec {
    private val MAGIC = "SBBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val MAX_CREATED_AT_BYTES = 128
    private const val HEADER_BYTES = 4 + 1 + 4 + SALT_BYTES + NONCE_BYTES + 4 + 8 + 2 + MAX_CREATED_AT_BYTES
    private const val ITERATIONS = 600_000

    fun encrypt(payload: ByteArray, passphrase: CharArray, metadata: BackupMetadata): ByteArray {
        require(passphrase.isNotEmpty()) { "Backup passphrase cannot be empty" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val header = header(salt, nonce, metadata)
        val key = derive(passphrase, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(payload)
        } finally {
            key.fill(0)
            salt.fill(0)
            nonce.fill(0)
        }
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): DecryptedBackup {
        require(passphrase.isNotEmpty()) { "Backup passphrase cannot be empty" }
        require(blob.size > HEADER_BYTES + TAG_BITS / 8) { "Backup is too short" }
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Bad backup magic" }
        require(blob[MAGIC.size] == VERSION) { "Unsupported backup version" }
        val buffer = ByteBuffer.wrap(blob, MAGIC.size + 1, HEADER_BYTES - MAGIC.size - 1)
        val iterations = buffer.int
        require(iterations >= MIN_ITERATIONS) { "Backup KDF cost is too low" }
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val nonce = ByteArray(NONCE_BYTES).also { buffer.get(it) }
        val schemaVersion = buffer.int
        val recordCount = buffer.long
        val createdLength = buffer.short.toInt() and 0xffff
        require(createdLength <= MAX_CREATED_AT_BYTES) { "Malformed backup timestamp" }
        val createdBytes = ByteArray(MAX_CREATED_AT_BYTES).also { buffer.get(it) }
        val metadata = BackupMetadata(
            schemaVersion,
            String(createdBytes, 0, createdLength, Charsets.UTF_8),
            recordCount,
        )
        createdBytes.fill(0)
        val key = derive(passphrase, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(blob, 0, HEADER_BYTES)
            DecryptedBackup(metadata, cipher.doFinal(blob, HEADER_BYTES, blob.size - HEADER_BYTES))
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("Backup authentication failed", e)
        } finally {
            key.fill(0)
            salt.fill(0)
            nonce.fill(0)
        }
    }

    private fun header(salt: ByteArray, nonce: ByteArray, metadata: BackupMetadata): ByteArray {
        val created = metadata.createdAt.toByteArray(Charsets.UTF_8)
        require(created.size <= MAX_CREATED_AT_BYTES) { "Backup timestamp is too long" }
        val createdField = ByteArray(MAX_CREATED_AT_BYTES).also { created.copyInto(it) }
        return ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).put(VERSION).putInt(ITERATIONS)
            .put(salt).put(nonce).putInt(metadata.schemaVersion).putLong(metadata.recordCount)
            .putShort(created.size.toShort()).put(createdField)
            .array()
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private const val MIN_ITERATIONS = 100_000
}
