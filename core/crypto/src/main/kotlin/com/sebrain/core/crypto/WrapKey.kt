package com.sebrain.core.crypto

import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** K_wrap: AES-256-GCM in StrongBox, with TEE as fallback, usable only while the device is unlocked. */
class WrapKey(
    val alias: String = DEFAULT_ALIAS,
    private val preferStrongBox: Boolean = true,
) {
    fun existing(): SecretKey? = keystoreCall {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        store.getKey(alias, null) as SecretKey?
    }

    fun getOrCreate(): SecretKey = existing() ?: keystoreCall {
        if (!preferStrongBox) {
            generate(strongBox = false)
        } else {
            try {
                generate(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
        }
    }

    /** A `KeyProperties.SECURITY_LEVEL_*` value, or null if the key does not exist. */
    fun securityLevel(): Int? = existing()?.let { key ->
        keystoreCall {
            val factory = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
            (factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo).securityLevel
        }
    }

    fun delete() = keystoreCall {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(KEY_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    internal companion object {
        const val DEFAULT_ALIAS = "sebrain_k_wrap"
        private const val PROVIDER = "AndroidKeyStore"
        private const val KEY_BITS = 256

        private inline fun <T> keystoreCall(block: () -> T): T = try {
            block()
        } catch (e: StrongBoxUnavailableException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw mapFailure(e)
        } catch (e: java.security.ProviderException) {
            throw mapFailure(e)
        }

        /** The lock rejection arrives wrapped, so the whole cause chain has to be inspected. */
        fun isDeviceLocked(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(MAX_CAUSES).any {
            it is UserNotAuthenticatedException ||
                (it is KeyStoreException && it.numericErrorCode == KeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED)
        }

        fun mapFailure(error: Throwable): VaultKeyException =
            if (isDeviceLocked(error)) {
                VaultKeyException.DeviceLocked(error)
            } else {
                VaultKeyException.KeystoreFailure(error)
            }

        private const val MAX_CAUSES = 8
    }
}
