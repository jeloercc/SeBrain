package com.sebrain.ingest

import android.content.Context
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class MiniBrain(val name: String, val sources: Set<String>)

/** Stores mini-brain filters encrypted, without duplicating vault content. */
class BrainStore(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    @Synchronized
    fun load(): List<MiniBrain> {
        if (!file.exists()) return emptyList()
        val blob = file.readBytes()
        require(blob.size > IV_BYTES) { "Malformed mini-brain store" }
        val plaintext = decrypt(blob)
        return try {
            val array = JSONArray(String(plaintext, Charsets.UTF_8))
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                MiniBrain(
                    name = item.getString("name"),
                    sources = item.getJSONArray("sources").let { sources ->
                        List(sources.length()) { sources.getString(it) }.toSet()
                    },
                )
            }
        } catch (e: JSONException) {
            throw IllegalStateException("Malformed mini-brain store", e)
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun save(brains: List<MiniBrain>) {
        val normalized = brains.map { brain ->
            require(brain.name.trim().isNotEmpty()) { "Mini-brain name cannot be empty" }
            require(brain.name.length <= 80) { "Mini-brain name is too long" }
            MiniBrain(brain.name.trim(), brain.sources.map(String::trim).filter(String::isNotEmpty).toSet())
        }.distinctBy { it.name.lowercase() }
        val array = JSONArray()
        normalized.sortedBy { it.name.lowercase() }.forEach { brain ->
            array.put(
                JSONObject()
                    .put("name", brain.name)
                    .put("sources", JSONArray(brain.sources.sorted())),
            )
        }
        val plaintext = array.toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val blob = cipher.iv + cipher.doFinal(plaintext)
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeBytes(blob)
            check(temporary.renameTo(file)) { "Could not store mini-brains" }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
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
        private const val FILE_NAME = "mini-brains.bin"
        private const val KEY_ALIAS = "sebrain.mini.brains"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
