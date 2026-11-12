package com.sebrain.diag

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Outbox spike. Trigger from adb, also with the screen off:
 * `am broadcast -n com.sebrain.diag/.OutboxProbeReceiver --es op prep|locked|unlocked`
 */
class OutboxProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val locked = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        val dir = File(context.noBackupFilesDir, "outbox_probe").apply { mkdirs() }
        val op = intent.getStringExtra("op")
        Diag.info("OutboxProbe op=$op deviceLocked=$locked")
        try {
            when (op) {
                "prep" -> prep(dir)
                "locked" -> locked(dir, locked)
                "unlocked" -> unlocked(dir, locked)
                else -> Diag.info("OutboxProbe: unknown op")
            }
        } catch (t: Throwable) {
            Diag.error("OutboxProbe $op", t)
            Diag.result("OutboxProbe $op", "FAIL", "-", Diag.describe(t))
        }
    }

    private fun prep(dir: File) {
        val store = keyStore()
        listOf(OUTBOX_ALIAS, INBOX_ALIAS).forEach { store.deleteEntry(it) }
        generate(OUTBOX_ALIAS, unlockedRequired = true)
        generate(INBOX_ALIAS, unlockedRequired = false)
        for (alias in listOf(OUTBOX_ALIAS, INBOX_ALIAS)) {
            val entry = store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            val level = KeyFactory.getInstance(entry.privateKey.algorithm, PROVIDER)
                .getKeySpec(entry.privateKey, KeyInfo::class.java).securityLevel
            Diag.result(
                "Outbox prep $alias keygen", if (level == KeyProperties.SECURITY_LEVEL_STRONGBOX) "PASS" else "FAIL",
                "securityLevel=$level", "2 is STRONGBOX",
            )
        }
        File(dir, "public.der").writeBytes(publicKey(OUTBOX_ALIAS).encoded)
        ecdhControl("Outbox prep unlocked control", OUTBOX_ALIAS)
    }

    private fun locked(dir: File, locked: Boolean) {
        if (!locked) {
            Diag.result("Outbox locked state", "FAIL", "deviceLocked=false", "the device must be locked")
            return
        }
        // (a) ECDH with the unlock-required private key must fail.
        try {
            agree(privateKey(OUTBOX_ALIAS), ephemeral().public)
            Diag.result("Outbox (a) ECDH while locked", "FAIL", "no error", "ECDH succeeded while locked")
        } catch (t: Exception) {
            Diag.result("Outbox (a) ECDH while locked", "PASS (rejected)", t.javaClass.simpleName, Diag.describe(t))
        }

        // (b) The public key is readable.
        val public = publicKey(OUTBOX_ALIAS)
        val same = public.encoded.contentEquals(File(dir, "public.der").readBytes())
        Diag.result("Outbox (b) read public key while locked", if (same) "PASS" else "FAIL", "${public.encoded.size} bytes", "matches the key recorded at prep: $same")

        // (c) Encrypting to the public key uses software ECDH only.
        val pair = ephemeral()
        val secret = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(public, true); generateSecret() }
        val key = hkdf(secret)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(PLAINTEXT)
        }
        val ephemeralBytes = pair.public.encoded
        File(dir, "entry.bin").writeBytes(
            ByteBuffer.allocate(4 + ephemeralBytes.size + nonce.size + ciphertext.size)
                .putInt(ephemeralBytes.size).put(ephemeralBytes).put(nonce).put(ciphertext).array(),
        )
        secret.fill(0)
        key.fill(0)
        Diag.result("Outbox (c) encrypt to public key while locked", "PASS", "${ciphertext.size} bytes", "software ECDH with an ephemeral key")

        // (d) The inbox key without an unlock requirement must work.
        ecdhControl("Outbox (d) ECDH with BALANCED K_inbox while locked", INBOX_ALIAS)
    }

    private fun unlocked(dir: File, locked: Boolean) {
        if (locked) {
            Diag.result("Outbox unlocked state", "FAIL", "deviceLocked=true", "unlock the device first")
            return
        }
        ecdhControl("Outbox (a) ECDH after unlock", OUTBOX_ALIAS)
        val blob = ByteBuffer.wrap(File(dir, "entry.bin").readBytes())
        val ephemeralBytes = ByteArray(blob.int).also { blob.get(it) }
        val nonce = ByteArray(12).also { blob.get(it) }
        val ciphertext = ByteArray(blob.remaining()).also { blob.get(it) }
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ephemeralBytes))
        val secret = agree(privateKey(OUTBOX_ALIAS), peer)
        val key = hkdf(secret)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(ciphertext)
        }
        val same = plain.contentEquals(PLAINTEXT)
        Diag.result("Outbox (c) decrypt entry after unlock", if (same) "PASS" else "FAIL", "${plain.size} bytes", "round trip of the entry written while locked")
        keyStore().apply { deleteEntry(OUTBOX_ALIAS); deleteEntry(INBOX_ALIAS) }
        dir.deleteRecursively()
    }

    private fun ecdhControl(test: String, alias: String) {
        val pair = ephemeral()
        val keystoreSide = agree(privateKey(alias), pair.public)
        val softwareSide = KeyAgreement.getInstance("ECDH").run {
            init(pair.private); doPhase(publicKey(alias), true); generateSecret()
        }
        val same = keystoreSide.contentEquals(softwareSide)
        Diag.result(test, if (same) "PASS" else "FAIL", "secrets match: $same", alias)
        keystoreSide.fill(0)
        softwareSide.fill(0)
    }

    private fun generate(alias: String, unlockedRequired: Boolean) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setIsStrongBoxBacked(true)
                    .setUnlockedDeviceRequired(unlockedRequired)
                    .build(),
            )
        }.generateKeyPair()
    }

    private fun ephemeral() =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun agree(private: PrivateKey, peer: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH", PROVIDER).run { init(private); doPhase(peer, true); generateSecret() }

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun privateKey(alias: String) = keyStore().getKey(alias, null) as PrivateKey

    private fun publicKey(alias: String): PublicKey = keyStore().getCertificate(alias).publicKey

    private fun hkdf(ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update("sebrain/outbox/v1".toByteArray())
        mac.update(1)
        return mac.doFinal().also { prk.fill(0) }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val OUTBOX_ALIAS = "diag_k_outbox"
        const val INBOX_ALIAS = "diag_k_inbox_balanced"
        val PLAINTEXT = "synthetic outbox entry".toByteArray()
    }
}
