package com.sebrain.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.sebrain.core.net.RemoteProvider
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * The external AI providers the user added, with their API keys. The list is one file encrypted
 * with AES-256-GCM under a Keystore key (StrongBox when the phone accepts it), so no key is
 * ever on disk in plain text, and the file is excluded from backups like the rest of
 * `noBackupFilesDir`. An API key is read only to send a request.
 */
class ProviderStore(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    /** A provider and its key, as stored. */
    class Entry(val provider: RemoteProvider, val apiKey: String)

    @Synchronized
    fun list(): List<RemoteProvider> = load().map { it.provider }

    @Synchronized
    fun apiKey(id: String): String? = load().firstOrNull { it.provider.id == id }?.apiKey

    /** Adds a provider, or replaces the one with the same id. A blank [apiKey] keeps the stored one. */
    @Synchronized
    fun save(provider: RemoteProvider, apiKey: String): RemoteProvider {
        val current = load()
        val id = provider.id.ifBlank { UUID.randomUUID().toString() }
        val key = apiKey.trim().ifEmpty { current.firstOrNull { it.provider.id == id }?.apiKey.orEmpty() }
        require(key.isNotEmpty()) { "An API key is needed" }
        RemoteChatEndpoint.check(provider.baseUrl)
        val saved = provider.copy(id = id, name = provider.name.trim(), baseUrl = provider.baseUrl.trim(), model = provider.model.trim())
        write(current.filterNot { it.provider.id == id } + Entry(saved, key))
        return saved
    }

    @Synchronized
    fun remove(id: String) = write(load().filterNot { it.provider.id == id })

    private fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        val blob = file.readBytes()
        if (blob.size <= IV_BYTES) return emptyList()
        val plain = Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }
        return try {
            val array = JSONArray(String(plain, Charsets.UTF_8))
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                Entry(
                    RemoteProvider(item.getString("id"), item.getString("name"), item.getString("baseUrl"), item.getString("model")),
                    item.getString("apiKey"),
                )
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun write(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().put("id", entry.provider.id).put("name", entry.provider.name).put("baseUrl", entry.provider.baseUrl)
                    .put("model", entry.provider.model).put("apiKey", entry.apiKey),
            )
        }
        val plain = array.toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val blob = cipher.iv + cipher.doFinal(plain)
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeBytes(blob)
            check(temporary.renameTo(file)) { "Could not store the providers" }
        } finally {
            plain.fill(0)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        return try {
            generate(strongBox = true)
        } catch (_: GeneralSecurityException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance("AES", KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    companion object {
        private const val FILE_NAME = "ai-providers.bin"
        private const val KEY_ALIAS = "sebrain.ai.providers"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** Ready-made addresses; the user still picks the model and pastes the key. */
        val PRESETS = listOf(
            RemoteProvider("", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-5.2"),
            RemoteProvider("", "OpenAI", "https://api.openai.com/v1", ""),
            RemoteProvider("", "Anthropic", "https://api.anthropic.com/v1", ""),
            RemoteProvider("", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", ""),
            RemoteProvider("", "Groq", "https://api.groq.com/openai/v1", ""),
            RemoteProvider("", "Custom", "https://", ""),
        )
    }
}

/** Rules for a provider's address, shared by the store and the settings screen. */
object RemoteChatEndpoint {
    fun check(baseUrl: String) {
        com.sebrain.core.net.RemoteChat.endpoint(baseUrl)
    }
}
