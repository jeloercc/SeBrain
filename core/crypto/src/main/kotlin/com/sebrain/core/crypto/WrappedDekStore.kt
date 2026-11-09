package com.sebrain.core.crypto

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the 32-byte DEK wrapped with K_wrap.
 *
 * Blob format: magic "SBDK" (4) | version (1) | IV (12) | ciphertext + GCM tag (48).
 * Magic and version are authenticated as AAD. Callers must zero the returned arrays.
 */
class WrappedDekStore(
    val blobFile: File,
    private val wrapKey: WrapKey = WrapKey(),
) {
    fun exists(): Boolean = blobFile.exists()

    /** Generates the DEK once, wraps it and stores it atomically. */
    fun createAndStore(): ByteArray {
        check(!blobFile.exists()) { "A wrapped DEK already exists at ${blobFile.path}" }
        val dek = ByteArray(DEK_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            writeAtomically(wrap(wrapKey.getOrCreate(), dek))
            return dek
        } catch (e: Throwable) {
            dek.fill(0)
            throw e
        }
    }

    fun unwrap(): ByteArray {
        if (!blobFile.exists()) throw VaultKeyException.WrappedDekMissing(blobFile.path)
        val blob = blobFile.readBytes()
        if (blob.size != BLOB_BYTES) corrupted("unexpected size ${blob.size}")
        if (!blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) corrupted("bad magic")
        if (blob[MAGIC.size] != VERSION) corrupted("unsupported version ${blob[MAGIC.size]}")

        val key = wrapKey.existing() ?: throw VaultKeyException.WrapKeyMissing(wrapKey.alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, HEADER_BYTES, IV_BYTES))
        } catch (e: GeneralSecurityException) {
            throw WrapKey.mapFailure(e)
        }
        return try {
            cipher.updateAAD(blob, 0, HEADER_BYTES)
            val offset = HEADER_BYTES + IV_BYTES
            cipher.doFinal(blob, offset, blob.size - offset).also {
                if (it.size != DEK_BYTES) {
                    it.fill(0)
                    corrupted("unexpected DEK size")
                }
            }
        } catch (e: GeneralSecurityException) {
            if (WrapKey.isDeviceLocked(e)) throw VaultKeyException.DeviceLocked(e)
            throw VaultKeyException.WrappedDekCorrupted(blobFile.path, "authentication failed", e)
        }
    }

    private fun corrupted(detail: String): Nothing =
        throw VaultKeyException.WrappedDekCorrupted(blobFile.path, detail)

    private fun wrap(key: SecretKey, dek: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val header = MAGIC + VERSION
        val ciphertext = try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(header)
            cipher.doFinal(dek)
        } catch (e: GeneralSecurityException) {
            throw WrapKey.mapFailure(e)
        }
        return ByteBuffer.allocate(BLOB_BYTES).put(header).put(cipher.iv).put(ciphertext).array()
    }

    private fun writeAtomically(blob: ByteArray) {
        blobFile.parentFile?.mkdirs()
        val temporary = File(blobFile.path + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(blob)
            output.fd.sync()
        }
        check(temporary.renameTo(blobFile)) { "Could not store the wrapped DEK at ${blobFile.path}" }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEK_BYTES = 32
        const val VERSION: Byte = 1
        val MAGIC = "SBDK".toByteArray(Charsets.US_ASCII)
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val HEADER_BYTES = 5
        const val BLOB_BYTES = HEADER_BYTES + IV_BYTES + DEK_BYTES + TAG_BITS / 8
    }
}
