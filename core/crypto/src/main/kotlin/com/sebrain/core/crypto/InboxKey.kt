package com.sebrain.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/** Which K_inbox new segments use. Each mode has its own key, because Keystore properties are fixed. */
enum class InboxMode(val keyId: Byte, internal val alias: String, internal val unlockedRequired: Boolean) {
    STRICT(1, "sebrain_k_inbox_strict", true),
    BALANCED(2, "sebrain_k_inbox_balanced", false),
    ;

    companion object {
        /** Decided on 2026-11-09. */
        val DEFAULT = BALANCED

        fun fromKeyId(keyId: Byte): InboxMode? = entries.firstOrNull { it.keyId == keyId }
    }
}

/**
 * K_inbox: EC P-256 key agreement pair in StrongBox, with TEE as fallback.
 * [aliasSuffix] exists so tests do not touch the real keys.
 */
class InboxKey(private val mode: InboxMode, aliasSuffix: String = "") {
    private val alias = mode.alias + aliasSuffix

    val keyId: Byte get() = mode.keyId

    fun exists(): Boolean = keyStore().containsAlias(alias)

    /** The public key, creating the pair on first use. It can be read while the device is locked. */
    fun publicKey(): PublicKey = synchronized(LOCK) {
        if (!exists()) generate()
        keyStore().getCertificate(alias).publicKey
    }

    /** ECDH with the private key. Fails with [VaultKeyException.DeviceLocked] if the key needs an unlocked device. */
    fun agree(peer: PublicKey): ByteArray {
        val private = privateKey() ?: throw VaultKeyException.InboxKeyMissing(alias)
        return try {
            KeyAgreement.getInstance("ECDH", PROVIDER).run {
                init(private)
                doPhase(peer, true)
                generateSecret()
            }
        } catch (e: GeneralSecurityException) {
            throw WrapKey.mapFailure(e)
        } catch (e: ProviderException) {
            throw WrapKey.mapFailure(e)
        }
    }

    /** A `KeyProperties.SECURITY_LEVEL_*` value, or null if the key does not exist. */
    fun securityLevel(): Int? = privateKey()?.let { key ->
        KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java).securityLevel
    }

    /** Deletes the key. Call only when no pending segment references [keyId]. */
    fun delete() = keyStore().deleteEntry(alias)

    private fun privateKey(): PrivateKey? = keyStore().getKey(alias, null) as PrivateKey?

    private fun generate() {
        try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setUnlockedDeviceRequired(mode.unlockedRequired)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private val LOCK = Any()

        /** ECDH with the real K_inbox selected by a segment's key id. */
        fun agree(keyId: Byte, peer: PublicKey): ByteArray {
            val mode = InboxMode.fromKeyId(keyId) ?: throw VaultKeyException.UnknownInboxKey(keyId)
            return InboxKey(mode).agree(peer)
        }
    }
}
