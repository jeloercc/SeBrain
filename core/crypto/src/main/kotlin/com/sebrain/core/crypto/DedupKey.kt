package com.sebrain.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * K_dedup: HMAC-SHA256 key in StrongBox, with TEE as fallback. It has no unlock requirement,
 * because ingestion deduplicates while the device is locked.
 *
 * Tags replace plain content hashes on disk: without this key, a tag cannot be used to confirm
 * whether a known text was captured.
 */
class DedupKey(private val alias: String = DEFAULT_ALIAS) {
    /**
     * HMAC of a content digest. The input is the 32-byte SHA-256 of the content, not the content,
     * so the secure element processes a small fixed-size input.
     */
    fun tag(contentSha256: ByteArray): ByteArray {
        require(contentSha256.size == 32) { "Expected a SHA-256 digest" }
        return try {
            Mac.getInstance(ALGORITHM).run {
                init(getOrCreate())
                doFinal(contentSha256)
            }
        } catch (e: GeneralSecurityException) {
            throw WrapKey.mapFailure(e)
        } catch (e: ProviderException) {
            throw WrapKey.mapFailure(e)
        }
    }

    /** A `KeyProperties.SECURITY_LEVEL_*` value, or null if the key does not exist. */
    fun securityLevel(): Int? = existing()?.let { key ->
        (SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo)
            .securityLevel
    }

    /** Deleting the key makes the stored tags useless; the tag index must be deleted with it. */
    fun delete() = keyStore().deleteEntry(alias)

    private fun existing(): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    private fun getOrCreate(): SecretKey = synchronized(LOCK) {
        existing() ?: try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val DEFAULT_ALIAS = "sebrain_k_dedup"
        const val PROVIDER = "AndroidKeyStore"
        const val ALGORITHM = "HmacSHA256"
        val LOCK = Any()
    }
}
