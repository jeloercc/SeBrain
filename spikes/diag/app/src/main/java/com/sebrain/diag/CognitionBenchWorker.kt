package com.sebrain.diag

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
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
 * Benchmark de CognitionWorker: por segmento mide ECDH (StrongBox), HKDF, AES-GCM y
 * extracción JSON con LiteRT-LM. La ingesta simulada (fuera del cronómetro) cifra con
 * ECIES contra K_inbox, igual que la jerarquía actual: una efímera por segmento.
 */
class CognitionBenchWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val n = inputData.getInt(KEY_BATCH, 10)
        val fg = inputData.getBoolean(KEY_FG, true)
        val label = if (fg) "Cognition lote $n" else "Cognition bg lote $n"
        if (fg) setForeground(foregroundInfo(label))
        logEnvironment("$label inicio")

        return try {
            run(n, label)
            logEnvironment("$label fin")
            Result.success()
        } catch (t: Throwable) {
            Diag.error(label, t)
            Diag.result(label, "FAIL", "-", Diag.describe(t))
            Result.failure()
        }
    }

    private fun run(n: Int, label: String) {
        val model = File(applicationContext.filesDir, "models/$MODEL_FILE")
        check(model.exists()) { "Falta el modelo en ${model.path}" }

        val (sha, shaMs) = timedMs { sha256(model) }
        Diag.info("$label SHA-256 del modelo en %.0f ms (fuera del cronómetro)".format(shaMs))
        check(sha == MODEL_SHA256) { "SHA-256 no coincide: $sha" }

        val inbox = inboxKey()
        val inboxPub = inbox.second
        val dir = File(applicationContext.noBackupFilesDir, "bench_inbox").apply {
            deleteRecursively(); mkdirs()
        }
        val (_, ingestMs) = timedMs {
            for (i in 0 until n) ingest(dir, i, inboxPub, SAMPLES[i % SAMPLES.size].format(i))
        }
        Diag.info("$label ingesta simulada de $n segmentos en %.0f ms (fuera del cronómetro)".format(ingestMs))

        val batchStart = System.nanoTime()
        val (engine, initMs) = try { timedMs {
            Engine(
                EngineConfig(
                    modelPath = model.path,
                    backend = Backend.NPU(applicationContext.applicationInfo.nativeLibraryDir),
                    cacheDir = applicationContext.cacheDir.path,
                ),
            ).also { it.initialize() }
        } } catch (t: Throwable) {
            Diag.error("$label carga del motor NPU", t)
            Diag.result("$label carga del motor", "FAIL", "-", "Backend.NPU; ${Diag.describe(t)}")
            throw t
        }
        Diag.result("$label carga del motor", "PASS", "%.0f ms".format(initMs), "Backend.NPU; $MODEL_FILE")

        val ecdh = ArrayList<Double>(n)
        val hkdf = ArrayList<Double>(n)
        val aes = ArrayList<Double>(n)
        val llm = ArrayList<Double>(n)
        val total = ArrayList<Double>(n)
        var jsonOk = 0
        var emptyTitle = 0
        val power = applicationContext.getSystemService(PowerManager::class.java)
        val thermal = IntArray(8)
        engine.use {
            for (i in 0 until n) {
                if (isStopped) {
                    Diag.result("$label detenido", "INFO", "seg $i", "isStopped=true; stopReason=$stopReason")
                    return
                }
                if (!inputData.getBoolean(KEY_FG, true) && (i == 0 || i == n - 1)) logEnvironment("$label seg $i")
                val thermalStatus = power.currentThermalStatus
                val headroom = power.getThermalHeadroom(10)
                thermal[thermalStatus.coerceIn(0, 7)]++
                val t0 = System.nanoTime()
                val blob = File(dir, "seg_$i.bin").readBytes()
                val seg = Segment.parse(blob)

                val (z, ecdhMs) = timedMs { ecdhKeystore(inbox.first, seg.ephemeral) }
                val (kSeg, hkdfMs) = timedMs { hkdf(z, INFO) }
                z.fill(0)
                val (plain, aesMs) = timedMs { decrypt(kSeg, seg) }
                kSeg.fill(0)
                val text = String(plain, Charsets.UTF_8)
                plain.fill(0)

                val (out, llmMs) = timedMs { extract(engine, text) }
                val totalMs = (System.nanoTime() - t0) / 1_000_000.0

                val parsed = runCatching { org.json.JSONObject(out) }.getOrNull()
                val valid = parsed != null
                if (valid) jsonOk++
                val titleEmpty = parsed != null &&
                    parsed.optString("titulo").trim().trim(':', ' ', '"').isEmpty()
                if (titleEmpty) emptyTitle++
                ecdh += ecdhMs; hkdf += hkdfMs; aes += aesMs; llm += llmMs; total += totalMs
                Diag.result(
                    "$label seg $i", if (valid) "PASS" else "JSON_INVALIDO",
                    "total=%.0f ms".format(totalMs),
                    "ecdh=%.2f hkdf=%.3f aes=%.3f llm=%.0f ms; salida=%d chars; ecdh%%=%.3f; thermal=%d headroom10s=%.3f; tituloVacio=%b"
                        .format(ecdhMs, hkdfMs, aesMs, llmMs, out.length, 100 * ecdhMs / totalMs, thermalStatus, headroom, titleEmpty),
                )
                if (!valid) {
                    // Texto sintético: se registra completo para diagnosticar el fallo.
                    out.replace('\n', ' ').chunked(800).forEachIndexed { k, part ->
                        Diag.info("$label salida invalida seg $i parte $k: $part")
                    }
                }
                if (i == 0) Diag.info("$label salida seg 0: ${out.replace('\n', ' ').take(400)}")
                File(dir, "seg_$i.bin").delete()
            }
        }
        val wallMs = (System.nanoTime() - batchStart) / 1_000_000.0

        Diag.result("$label ECDH", "PASS", "%.2f ms".format(ecdh.average()), stats(ecdh))
        Diag.result("$label HKDF", "PASS", "%.3f ms".format(hkdf.average()), stats(hkdf))
        Diag.result("$label AES-GCM", "PASS", "%.3f ms".format(aes.average()), stats(aes))
        Diag.result("$label LLM", "PASS", "%.0f ms".format(llm.average()), stats(llm))
        Diag.result("$label total/seg", "PASS", "%.0f ms".format(total.average()), stats(total))
        Diag.result(
            "$label ECDH % del total", if (100 * ecdh.sum() / total.sum() < 1) "< 1 %" else ">= 1 %",
            "%.3f %%".format(100 * ecdh.sum() / total.sum()),
            "suma ecdh=%.0f ms / suma total=%.0f ms; con carga del motor: %.3f %% de %.0f ms; JSON válido %d/%d; título vacío %d; thermalStatus por segmento %s; maxOutputToken=%d"
                .format(ecdh.sum(), total.sum(), 100 * ecdh.sum() / wallMs, wallMs, jsonOk, n, emptyTitle,
                    thermal.withIndex().filter { it.value > 0 }.joinToString { "${it.index}:${it.value}" }, MAX_OUTPUT_TOKENS),
        )
    }

    private fun extract(engine: Engine, text: String): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM),
            maxOutputToken = MAX_OUTPUT_TOKENS,
            thinkingConfig = ThinkingConfig(false),
            enableResponseFormat = true,
        )
        return engine.createConversation(config).use { conversation ->
            val reply = conversation.sendMessage(text, responseFormat = ResponseFormat.json(SCHEMA))
            reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }

    // --- Cripto: ECIES por segmento (jerarquía actual, sin cambios) ---

    private fun inboxKey(): Pair<PrivateKey, PublicKey> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(INBOX_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(INBOX_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setIsStrongBoxBacked(true)
                    .build(),
            )
            kpg.generateKeyPair()
        }
        val entry = ks.getEntry(INBOX_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to entry.certificate.publicKey
    }

    private fun ingest(dir: File, i: Int, inboxPub: PublicKey, text: String) {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val eph = kpg.generateKeyPair()
        val z = KeyAgreement.getInstance("ECDH").run {
            init(eph.private); doPhase(inboxPub, true); generateSecret()
        }
        val kSeg = hkdf(z, INFO)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ephBytes = eph.public.encoded
        val header = ByteBuffer.allocate(4 + ephBytes.size).putInt(ephBytes.size).put(ephBytes).array()
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(kSeg, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
            doFinal(text.toByteArray(Charsets.UTF_8))
        }
        z.fill(0); kSeg.fill(0)
        File(dir, "seg_$i.bin").writeBytes(header + nonce + ct)
    }

    private fun ecdhKeystore(priv: PrivateKey, ephPub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH", "AndroidKeyStore").run {
            init(priv); doPhase(ephPub, true); generateSecret()
        }

    private fun decrypt(kSeg: ByteArray, seg: Segment): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(kSeg, "AES"), GCMParameterSpec(128, seg.nonce))
            updateAAD(seg.header)
            doFinal(seg.ciphertext)
        }

    /** HKDF-SHA256 (RFC 5869) con salt vacío y L = 32. */
    private fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info); mac.update(1)
        return mac.doFinal().also { prk.fill(0) }
    }

    private class Segment(val header: ByteArray, val ephemeral: PublicKey, val nonce: ByteArray, val ciphertext: ByteArray) {
        companion object {
            fun parse(blob: ByteArray): Segment {
                val len = ByteBuffer.wrap(blob, 0, 4).int
                val header = blob.copyOfRange(0, 4 + len)
                val eph = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(blob.copyOfRange(4, 4 + len)))
                val nonce = blob.copyOfRange(4 + len, 16 + len)
                return Segment(header, eph, nonce, blob.copyOfRange(16 + len, blob.size))
            }
        }
    }

    // --- Entorno ---

    private fun logEnvironment(label: String) {
        val state = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        val power = applicationContext.getSystemService(PowerManager::class.java)
        val battery = applicationContext.getSystemService(BatteryManager::class.java)
        Diag.info(
            "$label: importance=${state.importance} thermalStatus=${power.currentThermalStatus} " +
                "headroom10s=${power.getThermalHeadroom(10)} bateria=${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}% " +
                "cargando=${battery.isCharging} interactive=${power.isInteractive} deviceIdle=${power.isDeviceIdleMode} " +
                "lightIdle=${power.isDeviceLightIdleMode}",
        )
    }

    private fun foregroundInfo(label: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Benchmark", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(label)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun stats(xs: List<Double>): String {
        val s = xs.sorted()
        fun p(q: Double) = s[((s.size - 1) * q).toInt()]
        return "media=%.3f p50=%.3f p95=%.3f min=%.3f max=%.3f suma=%.1f n=%d ms"
            .format(xs.average(), p(0.5), p(0.95), s.first(), s.last(), xs.sum(), xs.size)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val r = input.read(buf); if (r < 0) break; md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_BATCH = "batch"
        const val KEY_FG = "fg"
        fun input(n: Int, fg: Boolean = true) = workDataOf(KEY_BATCH to n, KEY_FG to fg)
        fun sample(i: Int): String = SAMPLES[i % SAMPLES.size].format(i)

        private const val MAX_OUTPUT_TOKENS = 512
        private const val INBOX_ALIAS = "diag_k_inbox_bench"
        private const val CHANNEL = "bench"
        private const val NOTIFICATION_ID = 42
        private val INFO = "sebrain/seg/v1".toByteArray()

        const val MODEL_FILE = "gemma-4-E2B-it_Google_Tensor_G5.litertlm"
        const val MODEL_SHA256 = "62faebcfd101acb841c33249530430397e031eb17d4dd3d2a71193d135705f27"

        private const val SYSTEM =
            "Extrae información estructurada del texto del usuario. Responde solo con JSON válido " +
                "que siga el esquema: titulo (frase corta), personas, fechas (ISO 8601 si es posible), " +
                "temas y tareas pendientes. Listas vacías si no hay datos."

        private val SCHEMA = """
            {"type":"object","properties":{
              "titulo":{"type":"string"},
              "personas":{"type":"array","items":{"type":"string"}},
              "fechas":{"type":"array","items":{"type":"string"}},
              "temas":{"type":"array","items":{"type":"string"}},
              "tareas":{"type":"array","items":{"type":"string"}}},
             "required":["titulo","personas","fechas","temas","tareas"]}
        """.trimIndent()

        /** Textos sintéticos tipo notificación o contenido compartido. `%d` varía cada segmento. */
        private val SAMPLES = listOf(
            "WhatsApp · Laura Méndez: Hola! Al final la reunión con el equipo de diseño pasa al jueves 14 a las 10:30 en la sala Norte. Trae los bocetos del onboarding y la lista de pantallas pendientes (#%d). Avísale también a Diego que necesitamos su feedback sobre los colores antes del miércoles.",
            "Gmail · Banco Central: Su factura de la tarjeta terminada en 4821 por 1.245,90 EUR vence el 28 de noviembre. Puede consultar el detalle de movimientos en la app. Referencia %d. Si ya realizó el pago, ignore este mensaje.",
            "Artículo compartido desde Chrome: \"Cómo la fermentación láctica cambia el perfil nutricional de las verduras\". Un estudio de la Universidad de Wageningen publicado en octubre de 2026 analizó 40 variedades de col fermentada y encontró aumentos de vitamina K2 y una reducción del 30 %% en nitratos. Los autores, dirigidos por Anneke de Vries, recomiendan fermentar al menos 21 días a 18 °C. Nota %d.",
            "Calendario · Recordatorio: Cita con la dentista Dra. Patricia Ruiz mañana viernes a las 17:15. Clínica Sonrisa, calle Alcalá 112, 3.º izquierda. Llevar la tarjeta del seguro. Evento %d.",
            "Telegram · Grupo Escalada: Marcos: el sábado subimos a La Pedriza, quedamos a las 8:00 en la gasolinera de Manzanares. Somos 6. Ana lleva la cuerda de 70 m, yo los friends. Confirmad antes del viernes por la noche. Mensaje %d.",
            "Nota compartida: Ideas para el proyecto SeBrain (%d). 1) Probar EmbeddingGemma con 256 dimensiones. 2) Medir la latencia de FTS5 con 100k chunks. 3) Revisar con Carlos la política de retención de notificaciones. 4) Preparar demo para el 3 de diciembre.",
            "Slack · #infra · Javier Ortega: el despliegue de la versión 4.2 se retrasa al lunes 17 por el fallo en las migraciones de la base de datos. Rocío está revisando el script. Necesito que alguien actualice la página de estado antes de las 12. Incidencia %d.",
            "Amazon: Tu pedido %d (auriculares Sony WH-1000XM6 y funda) se ha enviado. Entrega estimada: martes 12 de noviembre entre las 9:00 y las 14:00. Transportista: SEUR.",
            "Correo de Elena Gutiérrez: Te paso el resumen de la llamada con el cliente. Quieren el presupuesto revisado antes del 20 de noviembre, con dos opciones: licencia anual y pago por uso. Pablo se encarga de la parte legal. Hilo %d.",
            "Mensajes · Mamá: no te olvides de que el domingo es el cumpleaños de la abuela Carmen, cumple 90. Comemos en casa de la tía Rosa a las 14:00. ¿Puedes encargar la tarta en la pastelería de siempre? Mensaje %d.",
        )
    }
}
