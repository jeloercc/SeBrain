package com.sebrain.diag

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

private const val PROVIDER = "AndroidKeyStore"
private const val EC_ALIAS = "diag_inbox_ec"
private const val AES_ALIAS = "diag_wrap_aes"
private const val ITERATIONS = 20

class KeystoreDiag(private val context: Context) {
    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun run() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        Diag.result(
            "device.lock_state",
            "INFO",
            "isDeviceSecure=${keyguard.isDeviceSecure} isDeviceLocked=${keyguard.isDeviceLocked}",
            "state when the Keystore tests start",
        )

        val hasStrongBox =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        Diag.result("FEATURE_STRONGBOX_KEYSTORE", if (hasStrongBox) "PASS" else "FAIL", "$hasStrongBox")

        val ecPair = generateEc()
        if (ecPair != null) {
            reportKeyInfoEc(ecPair)
            runEcdh(ecPair)
        }

        val aesKey = generateAes()
        if (aesKey != null) {
            reportKeyInfoAes(aesKey)
            runAes(aesKey)
        }
    }

    private fun generateEc(): KeyPair? {
        fun attempt(strongBox: Boolean): KeyPair {
            keyStore.deleteEntry(EC_ALIAS)
            val spec = KeyGenParameterSpec.Builder(EC_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setIsStrongBoxBacked(strongBox)
                .build()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            generator.initialize(spec)
            return generator.generateKeyPair()
        }

        try {
            val (pair, ms) = timedMs { attempt(strongBox = true) }
            Diag.result("EC P-256 AGREE_KEY keygen StrongBox", "PASS", "%.1f ms".format(ms))
            return pair
        } catch (t: Throwable) {
            Diag.error("EC P-256 AGREE_KEY keygen StrongBox", t)
            Diag.result("EC P-256 AGREE_KEY keygen StrongBox", "FAIL", "-", Diag.describe(t))
        }
        return try {
            val (pair, ms) = timedMs { attempt(strongBox = false) }
            Diag.result("EC P-256 AGREE_KEY keygen TEE (retry)", "PASS", "%.1f ms".format(ms))
            pair
        } catch (t: Throwable) {
            Diag.error("EC P-256 AGREE_KEY keygen TEE", t)
            Diag.result("EC P-256 AGREE_KEY keygen TEE (retry)", "FAIL", "-", Diag.describe(t))
            null
        }
    }

    private fun runEcdh(keystorePair: KeyPair) {
        val softwareGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC)
        softwareGen.initialize(ECGenParameterSpec("secp256r1"))
        Diag.info("ECDH: ephemeral with software provider '${softwareGen.provider.name}'")

        val ephemeralMs = ArrayList<Double>()
        val softwareAgreeMs = ArrayList<Double>()
        val keystoreAgreeMs = ArrayList<Double>()
        val totalMs = ArrayList<Double>()
        try {
            repeat(ITERATIONS) {
                val iterationStart = System.nanoTime()
                val (ephemeral, genMs) = timedMs { softwareGen.generateKeyPair() }

                // Sender side: software ephemeral private + Keystore public.
                val (sender, senderMs) = timedMs {
                    KeyAgreement.getInstance("ECDH").run {
                        init(ephemeral.private)
                        doPhase(keystorePair.public, true)
                        generateSecret()
                    }
                }
                // Receiver side: Keystore private + ephemeral public.
                val (receiver, receiverMs) = timedMs {
                    KeyAgreement.getInstance("ECDH", PROVIDER).run {
                        init(keystorePair.private)
                        doPhase(ephemeral.public, true)
                        generateSecret()
                    }
                }
                check(sender.contentEquals(receiver)) { "the ECDH secrets do not match" }
                ephemeralMs += genMs
                softwareAgreeMs += senderMs
                keystoreAgreeMs += receiverMs
                totalMs += (System.nanoTime() - iterationStart) / 1_000_000.0
            }
            val ks = Stats(keystoreAgreeMs)
            Diag.result(
                "ECDH Keystore side (Keystore private + ephemeral public)",
                "PASS",
                "%.2f ms average".format(ks.avg),
                "$ks; equal secrets on every iteration",
            )
            Diag.result("ECDH software side", "PASS", "%.2f ms average".format(Stats(softwareAgreeMs).avg), Stats(softwareAgreeMs).toString())
            Diag.result("Ephemeral keygen software", "PASS", "%.2f ms average".format(Stats(ephemeralMs).avg), Stats(ephemeralMs).toString())
            Diag.result("Full ECDH (ephemeral + both sides)", "PASS", "%.2f ms average".format(Stats(totalMs).avg), Stats(totalMs).toString())
        } catch (t: Throwable) {
            Diag.error("ECDH", t)
            Diag.result("ECDH", "FAIL", "-", Diag.describe(t))
        }
    }

    private fun generateAes(): SecretKey? {
        fun attempt(strongBox: Boolean): SecretKey {
            keyStore.deleteEntry(AES_ALIAS)
            val spec = KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUnlockedDeviceRequired(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(spec)
            return generator.generateKey()
        }

        try {
            val (key, ms) = timedMs { attempt(strongBox = true) }
            Diag.result("AES-256-GCM keygen StrongBox + UnlockedDeviceRequired", "PASS", "%.1f ms".format(ms))
            return key
        } catch (t: Throwable) {
            Diag.error("AES-256-GCM keygen StrongBox", t)
            Diag.result("AES-256-GCM keygen StrongBox + UnlockedDeviceRequired", "FAIL", "-", Diag.describe(t))
        }
        return try {
            val (key, ms) = timedMs { attempt(strongBox = false) }
            Diag.result("AES-256-GCM keygen TEE (retry)", "PASS", "%.1f ms".format(ms))
            key
        } catch (t: Throwable) {
            Diag.error("AES-256-GCM keygen TEE", t)
            Diag.result("AES-256-GCM keygen TEE (retry)", "FAIL", "-", Diag.describe(t))
            null
        }
    }

    private fun runAes(key: SecretKey) {
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapMs = ArrayList<Double>()
        val unwrapMs = ArrayList<Double>()
        try {
            repeat(ITERATIONS) {
                val (wrapped, encryptMs) = timedMs {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                    cipher.iv to cipher.doFinal(dek)
                }
                val (unwrapped, decryptMs) = timedMs {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.first))
                    cipher.doFinal(wrapped.second)
                }
                check(dek.contentEquals(unwrapped)) { "the unwrap does not match the DEK" }
                wrapMs += encryptMs
                unwrapMs += decryptMs
            }
            Diag.result("AES-GCM wrap 32 bytes", "PASS", "%.2f ms average".format(Stats(wrapMs).avg), Stats(wrapMs).toString())
            Diag.result("AES-GCM unwrap 32 bytes", "PASS", "%.2f ms average".format(Stats(unwrapMs).avg), Stats(unwrapMs).toString())
        } catch (t: Throwable) {
            Diag.error("AES-GCM wrap/unwrap", t)
            Diag.result("AES-GCM wrap/unwrap", "FAIL", "-", Diag.describe(t))
        } finally {
            dek.fill(0)
        }
    }

    private fun reportKeyInfoEc(pair: KeyPair) {
        try {
            val info = KeyFactory.getInstance(pair.private.algorithm, PROVIDER)
                .getKeySpec(pair.private, KeyInfo::class.java)
            Diag.result("KeyInfo EC", "INFO", levelName(info.securityLevel), "securityLevel=${info.securityLevel}")
        } catch (t: Throwable) {
            Diag.error("KeyInfo EC", t)
            Diag.result("KeyInfo EC", "FAIL", "-", Diag.describe(t))
        }
    }

    private fun reportKeyInfoAes(key: SecretKey) {
        try {
            val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            Diag.result(
                "KeyInfo AES",
                "INFO",
                levelName(info.securityLevel),
                "securityLevel=${info.securityLevel} size=${info.keySize}",
            )
        } catch (t: Throwable) {
            Diag.error("KeyInfo AES", t)
            Diag.result("KeyInfo AES", "FAIL", "-", Diag.describe(t))
        }
    }

    private fun levelName(level: Int) = when (level) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT"
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
        KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "UNKNOWN_SECURE"
        else -> "UNKNOWN($level)"
    }
}
