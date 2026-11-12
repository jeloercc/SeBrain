package com.sebrain.ingest

import android.content.Context
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONException

/** Encrypts integration package names before writing them outside the vault. */
class IntegrationPolicyStore(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    @Synchronized
    fun load(): IntegrationPolicy {
        if (!file.exists()) return IntegrationPolicy.denyAll()
        val blob = file.readBytes()
        require(blob.size > IV_BYTES) { "Malformed integration policy" }
        val plaintext = decrypt(blob)
        return try {
            val json = JSONArray(String(plaintext, Charsets.UTF_8))
            IntegrationPolicy.fromPackages(List(json.length()) { json.getString(it) })
        } catch (e: JSONException) {
            throw IllegalStateException("Malformed integration policy", e)
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun save(policy: IntegrationPolicy) {
        val plaintext = JSONArray(policy.packages().sorted()).toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(plaintext)
            val blob = cipher.iv + encrypted
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeBytes(blob)
            check(temporary.renameTo(file)) { "Could not store integration policy" }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
        )
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        return try {
            generate(true)
        } catch (_: GeneralSecurityException) {
            generate(false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance("AES", PROVIDER)
        val builder = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        generator.init(builder.build())
        return generator.generateKey()
    }

    companion object {
        private const val FILE_NAME = "integration-policy.bin"
        private const val KEY_ALIAS = "sebrain.integration.policy"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
