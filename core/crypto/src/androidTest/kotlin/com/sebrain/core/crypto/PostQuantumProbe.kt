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
        const val WARMUP = 10
        const val P95 = 0.95
        const val NANOS_PER_MS = 1e6
        const val KB = 1024
        /** About the size of a backup manifest. */
        const val MANIFEST_BYTES = 512
    }
}
