package com.sebrain.diag

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ALIAS = "diag_k_wrap_lock"

/**
 * Prueba `setUnlockedDeviceRequired` con el dispositivo bloqueado. Un receiver permite dispararla
 * desde adb con la pantalla apagada, cuando no hay Activity en primer plano:
 * `am broadcast -n com.sebrain.diag/.LockProbeReceiver --es op prep|probe --es label <texto>`
 */
class LockProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val blob = File(context.noBackupFilesDir, "lock_probe.bin")
        val label = intent.getStringExtra("label") ?: "sin etiqueta"
        try {
            when (intent.getStringExtra("op")) {
                "prep" -> prepare(blob)
                "probe" -> probe(context, blob, label)
                else -> Diag.info("LockProbe: op desconocida")
            }
        } catch (t: Throwable) {
            Diag.error("LockProbe", t)
            Diag.result("LockProbe $label", "FAIL", "-", Diag.describe(t))
        }
    }

    private fun prepare(blob: File) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(ALIAS)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUnlockedDeviceRequired(true)
                .setIsStrongBoxBacked(true)
                .build(),
        )
        val key = generator.generateKey()

        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(dek)
        DataOutputStream(blob.outputStream()).use { out ->
            for (part in listOf(iv, ciphertext, sha256(dek))) {
                out.writeInt(part.size)
                out.write(part)
            }
        }
        dek.fill(0)
        Diag.result("LockProbe preparación", "PASS", "K_wrap de prueba y blob creados", "StrongBox")
    }

    private fun probe(context: Context, blob: File, label: String) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val locked = keyguard.isDeviceLocked
        Diag.info("LockProbe $label: isDeviceLocked=$locked isDeviceSecure=${keyguard.isDeviceSecure}")

        val (iv, ciphertext, expected) = DataInputStream(blob.inputStream()).use { input ->
            List(3) { ByteArray(input.readInt()).also(input::readFully) }
        }
        attempt(label, "descifrar", locked) { key ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val dek = cipher.doFinal(ciphertext)
            check(sha256(dek).contentEquals(expected)) { "la DEK no coincide" }
            dek.fill(0)
        }
        attempt(label, "cifrar", locked) { key ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.doFinal(ByteArray(32))
        }
    }

    private fun attempt(label: String, op: String, locked: Boolean, block: (SecretKey) -> Unit) {
        val test = "UnlockedDeviceRequired $op, $label (isDeviceLocked=$locked)"
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            block(keyStore.getKey(ALIAS, null) as SecretKey)
            Diag.result(test, if (locked) "FAIL" else "PASS", "operación correcta", if (locked) "no se bloqueó" else "control positivo")
        } catch (t: Throwable) {
            Diag.error(test, t)
            val chain = generateSequence(t) { it.cause }.toList()
            val expected = chain.firstOrNull { it is UserNotAuthenticatedException || it is KeyStoreException }
            val code = chain.filterIsInstance<KeyStoreException>().firstOrNull()?.numericErrorCode
            val result = if (locked && expected != null) "PASS (rechazada)" else "FAIL"
            Diag.result(test, result, t.javaClass.simpleName, "numericErrorCode=$code; ${Diag.describe(t)}")
        }
    }

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
}
