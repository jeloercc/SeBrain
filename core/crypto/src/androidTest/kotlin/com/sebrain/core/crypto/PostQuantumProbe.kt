package com.sebrain.core.crypto

import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider selection for `docs/design-post-quantum.md` (implementation gate, steps 1 and 3).
 * Measured, not assumed: which providers of this Android build offer ML-KEM-768 and ML-DSA-65,
 * whether Android Keystore can make an ML-DSA key, and how fast a pinned Bouncy Castle is on the
 * phone. Bouncy Castle is used here as an explicit provider object, never installed with
 * `Security.addProvider`, and it is a test dependency only. Logged under SEBRAIN_EVAL; the test
 * never fails on what it finds.
 */
@RunWith(AndroidJUnit4::class)
class PostQuantumProbe {
    @Test
    fun providers() {
        log("pq android sdk=${Build.VERSION.SDK_INT} security patch=${Build.VERSION.SECURITY_PATCH}")
        for (provider in Security.getProviders()) {
            val pq = provider.services.filter { it.algorithm.contains("ML-KEM", true) || it.algorithm.contains("ML-DSA", true) || it.type == "KEM" }
            log("pq platform provider=${provider.name} (${provider.info.take(60)}) services=${pq.joinToString { "${it.type}/${it.algorithm}" }.ifEmpty { "none" }}")
        }
        platformTry("KeyPairGenerator ML-KEM") { KeyPairGenerator.getInstance("ML-KEM") }
        platformTry("Signature ML-DSA-65") { Signature.getInstance("ML-DSA-65") }
        platformTry("AndroidKeyStore ML-DSA") { KeyPairGenerator.getInstance("ML-DSA", "AndroidKeyStore") }
    }

    @Test
    fun bouncyCastleSpeed() {
        val bc: Provider = BouncyCastleProvider()
        log("pq bouncycastle ${bc.info}")
        val random = SecureRandom()

        // ML-KEM through Bouncy Castle's own API: no dependency on the platform's javax.crypto.KEM.
        val kemKeys = MLKEMKeyPairGenerator().apply { init(MLKEMKeyGenerationParameters(random, MLKEMParameters.ml_kem_768)) }
        val keyGen = timed { kemKeys.generateKeyPair() }
        val pair = kemKeys.generateKeyPair()
        val generator = MLKEMGenerator(random)
        var encapsulated = generator.generateEncapsulated(pair.public)
        val encaps = timed { encapsulated = generator.generateEncapsulated(pair.public) }
        val extractor = MLKEMExtractor(pair.private as MLKEMPrivateKeyParameters)
        val decaps = timed { extractor.extractSecret(encapsulated.encapsulation) }
        val same = extractor.extractSecret(encapsulated.encapsulation).contentEquals(encapsulated.secret)
        log("pq ML-KEM-768 keygen=$keyGen encaps=$encaps decaps=$decaps shared secrets match=$same ciphertext=${encapsulated.encapsulation.size} B")

        val dsaKeys = KeyPairGenerator.getInstance("ML-DSA", bc).apply { initialize(MLDSAParameterSpec.ml_dsa_65, random) }
        val dsaPair = dsaKeys.generateKeyPair()
        val message = ByteArray(MANIFEST_BYTES).also(random::nextBytes)
        var signature = ByteArray(0)
        val dsaGen = timed { dsaKeys.generateKeyPair() }
        val sign = timed { signature = Signature.getInstance("ML-DSA", bc).run { initSign(dsaPair.private, random); update(message); sign() } }
        var verified = false
        val verify = timed { verified = Signature.getInstance("ML-DSA", bc).run { initVerify(dsaPair.public); update(message); verify(signature) } }
        log("pq ML-DSA-65 keygen=$dsaGen sign=$sign verify=$verify verified=$verified signature=${signature.size} B pss=${Debug.getPss() / KB} MB")
    }

    /**
     * The platform's own ML-KEM and ML-DSA (Conscrypt), and ML-DSA keys in Android Keystore,
     * in StrongBox and in the TEE: what the design assumed did not exist.
     */
    @Test
    fun platformSpeed() {
        // Measured 2026-11-20: javax.crypto.KEM compiles against SDK 37 but is missing at run
        // time, and Conscrypt lists ML-KEM only as KeyPairGenerator and KeyFactory. Checked by
        // name so this class still loads where it is missing.
        platformTry("javax.crypto.KEM class") { Class.forName("javax.crypto.KEM") }
        runCatching {
            val keys = KeyPairGenerator.getInstance("ML-KEM-768", "AndroidOpenSSL")
            log("pq conscrypt ML-KEM-768 keygen=${timed { keys.generateKeyPair() }} (no encapsulation API on the platform)")
        }.onFailure { log("pq conscrypt ML-KEM-768 failed: ${it.javaClass.simpleName}: ${it.message?.take(120)}") }

        runCatching {
            val keys = KeyPairGenerator.getInstance("ML-DSA-65", "AndroidOpenSSL")
            val pair = keys.generateKeyPair()
            val message = ByteArray(MANIFEST_BYTES).also(SecureRandom()::nextBytes)
            var signature = ByteArray(0)
            val sign = timed { signature = Signature.getInstance("ML-DSA-65", "AndroidOpenSSL").run { initSign(pair.private); update(message); sign() } }
            log("pq conscrypt ML-DSA-65 sign=$sign signature=${signature.size} B")
        }.onFailure { log("pq conscrypt ML-DSA-65 failed: ${it.javaClass.simpleName}: ${it.message?.take(120)}") }

        for (strongBox in listOf(true, false)) keystoreMlDsa(strongBox)
    }

    private fun keystoreMlDsa(strongBox: Boolean) {
        val alias = "pq_probe_mldsa_${if (strongBox) "sb" else "tee"}"
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching {
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(alias, android.security.keystore.KeyProperties.PURPOSE_SIGN)
                .setIsStrongBoxBacked(strongBox).build()
            val start = System.nanoTime()
            val pair = KeyPairGenerator.getInstance("ML-DSA-65", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
            val genMs = (System.nanoTime() - start) / NANOS_PER_MS
            val info = java.security.KeyFactory.getInstance(pair.private.algorithm, "AndroidKeyStore")
                .getKeySpec(pair.private, android.security.keystore.KeyInfo::class.java)
            val message = ByteArray(MANIFEST_BYTES).also(SecureRandom()::nextBytes)
            var signature = ByteArray(0)
            val sign = timedFew { signature = Signature.getInstance("ML-DSA-65").run { initSign(pair.private); update(message); sign() } }
            val verified = Signature.getInstance("ML-DSA-65").run { initVerify(pair.public); update(message); verify(signature) }
            log("pq keystore ML-DSA-65 strongBox=$strongBox securityLevel=${info.securityLevel} keygen=%.0f ms sign=$sign verified=$verified".format(genMs))
        }.onFailure { log("pq keystore ML-DSA-65 strongBox=$strongBox failed: ${it.javaClass.simpleName}: ${it.message?.take(120)}") }
        runCatching { store.deleteEntry(alias) }
    }

    /** Like [timed], with fewer runs: a hardware key signs much more slowly. */
    private fun timedFew(block: () -> Unit): String {
        val ms = (1..FEW_RUNS).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / NANOS_PER_MS
        }.sorted()
        return "median %.1f ms max %.1f ms".format(ms[ms.size / 2], ms.last())
    }

    private fun platformTry(name: String, block: () -> Any) {
        val result = runCatching(block)
        log("pq platform $name: ${result.fold({ "available (${it.javaClass.name})" }, { "unavailable (${it.javaClass.simpleName}: ${it.message?.take(100)})" })}")
    }

    /** Median and p95 in ms over [RUNS] runs, after [WARMUP] unmeasured ones. */
    private fun timed(block: () -> Unit): String {
        repeat(WARMUP) { block() }
        val ms = (1..RUNS).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / NANOS_PER_MS
        }.sorted()
        return "median %.2f ms p95 %.2f ms".format(ms[ms.size / 2], ms[(ms.size * P95).toInt().coerceAtMost(ms.size - 1)])
    }

    private fun log(line: String) {
        Log.i("SEBRAIN_EVAL", line)
    }

    private companion object {
        const val RUNS = 100
        const val FEW_RUNS = 10
        const val WARMUP = 10
        const val P95 = 0.95
        const val NANOS_PER_MS = 1e6
        const val KB = 1024
        /** About the size of a backup manifest. */
        const val MANIFEST_BYTES = 512
    }
}
