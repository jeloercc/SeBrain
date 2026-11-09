package com.sebrain.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/** Which K_inbox new segments use. Each mode has its own key, because Keystore properties are fixed. */
enum class InboxMode(val keyId: Byte, internal val alias: String, internal val unlockedRequired: Boolean) {
    STRICT(1, "sebrain_k_inbox_strict", true),
    BALANCED(2, "sebrain_k_inbox_balanced", false),
    ;

    companion object {
        fun fromKeyId(keyId: Byte): InboxMode? = entries.firstOrNull { it.keyId == keyId }
    }
}

/** K_inbox: EC P-256 key agreement pair in StrongBox, with TEE as fallback. */
class InboxKey(private val mode: InboxMode) {
    val keyId: Byte get() = mode.keyId

    /** The public key, used by ingestion. It is available while the device is locked. */
    fun publicKey(): PublicKey {
        val store = keyStore()
        if (!store.containsAlias(mode.alias)) generate()
        return store.getCertificate(mode.alias).publicKey
    }

    /** The private key handle, or null if this mode's key does not exist. */
    fun privateKey(): PrivateKey? = keyStore().getKey(mode.alias, null) as PrivateKey?

    /** Deletes the key. Call only when no pending segment references [keyId]. */
    fun delete() = keyStore().deleteEntry(mode.alias)

    private fun generate() {
        try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(mode.alias, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setUnlockedDeviceRequired(mode.unlockedRequired)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
