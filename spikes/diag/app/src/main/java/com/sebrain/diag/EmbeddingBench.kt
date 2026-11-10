package com.sebrain.diag

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * EmbeddingGemma 2 Text 270M benchmark on one backend per process, so the peak memory is
 * per backend. Start with `--es mode embed --es backend npu|gpu|cpu --ei minutes <n>`.
 */
class EmbeddingBench(private val context: Context, private val backendName: String, private val sustainMinutes: Int) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val label = "Embed $backendName"

    fun run() {
        Diag.info("$label start: ${processState()}")
        try {
            bench()
        } catch (t: Throwable) {
            Diag.error(label, t)
            Diag.result(label, "FAIL", "-", Diag.describe(t))
        }
        Diag.info("$label end: ${processState()}")
        Diag.info("EMBED_DONE $backendName")
    }

    private fun bench() {
        val (file, sha, backend) = when (backendName) {
            "npu" -> Triple(NPU_FILE, NPU_SHA256, Backend.NPU(context.applicationInfo.nativeLibraryDir))
            "gpu" -> Triple(GENERIC_FILE, GENERIC_SHA256, Backend.GPU())
            "cpu" -> Triple(GENERIC_FILE, GENERIC_SHA256, Backend.CPU())
            else -> error("Unknown backend $backendName")
        }
        val model = File(context.filesDir, "models/$file")
        check(model.exists()) { "Model missing at ${model.path}" }
        check(sha256(model) == sha) { "SHA-256 mismatch for $file" }
        Diag.info("$label environment: ${environment()} rssMb=${rssMb("VmRSS")}")

        val (engine, loadMs) = timedMs {
            EmbeddingEngine(
                EmbeddingEngineConfig(modelPath = model.path, backend = backend, cacheDir = context.cacheDir.path),
            ).also { it.initialize() }
        }
        Diag.result("$label engine load", "PASS", "%.0f ms".format(loadMs), file)

        engine.use {
            val options = EmbeddingOptions(normalize = true, outputSize = DIMENSIONS)
            fun embed(text: String): FloatArray = engine.computeEmbedding(listOf(InputData.Text(text)), options).embedding

            repeat(WARMUP) { embed(MEDIUM) }
            for ((name, text) in listOf("short" to SHORT, "medium" to MEDIUM, "long" to LONG)) {
                // Each input size selects its own model signature, so each one is warmed up.
                val (_, firstMs) = timedMs { embed(text) }
                repeat(WARMUP - 1) { embed(text) }
                Diag.info("$label first call $name=%.1f ms".format(firstMs))
                val times = (1..ITERATIONS).map { timedMs { embed(text) }.second }
                Diag.result(
                    "$label latency $name", "PASS", "%.1f ms".format(times.average()),
                    "${stats(times)}; words=${text.split(' ').size} chars=${text.length}",
                )
            }

            val vector = embed(MEDIUM)
            val norm = sqrt(vector.sumOf { (it * it).toDouble() })
            val related = cosine(embed(QUERY), embed(RELATED))
            val unrelated = cosine(embed(QUERY), embed(UNRELATED))
            val sane = vector.size == DIMENSIONS && kotlin.math.abs(norm - 1.0) < 0.01 && related > unrelated
            Diag.result(
                "$label output", if (sane) "PASS" else "FAIL", "dims=${vector.size} norm=%.4f".format(norm),
                "cosine related=%.3f unrelated=%.3f".format(related, unrelated),
            )
            // Compared across backends by the script: the same text should give close vectors.
            Diag.info("$label vector head=${vector.take(8).joinToString(",") { "%.4f".format(it) }}")
            Diag.result("$label memory after quick pass", "INFO", "peak ${rssMb("VmHWM")} MB", "current ${rssMb("VmRSS")} MB")

            if (sustainMinutes > 0) sustain(::embed)
        }
    }

    private fun sustain(embed: (String) -> FloatArray) {
        val end = System.nanoTime() + sustainMinutes * 60_000_000_000L
        var windowStart = System.nanoTime()
        var windowCount = 0
        var total = 0
        var minute = 0
        var first = 0.0
        var last = 0.0
        var maxThermal = 0
        while (System.nanoTime() < end) {
            embed(MEDIUM)
            windowCount++
            total++
            val elapsed = System.nanoTime() - windowStart
            if (elapsed >= 60_000_000_000L) {
                minute++
                val perSecond = windowCount / (elapsed / 1e9)
                if (minute == 1) first = perSecond
                last = perSecond
                val thermal = power.currentThermalStatus
                if (thermal > maxThermal) maxThermal = thermal
                Diag.info(
                    "$label sustain minute=$minute texts/s=%.1f ms/text=%.1f ${environment()}".format(perSecond, 1000 / perSecond),
                )
                windowStart = System.nanoTime()
                windowCount = 0
            }
        }
        Diag.result(
            "$label sustained $sustainMinutes min", "PASS", "%.1f texts/s in the last minute".format(last),
            "first minute %.1f texts/s; change %.1f %%; total=%d; max thermalStatus=%d; batteryC=%.1f; peak %d MB"
                .format(first, 100 * (last - first) / first, total, maxThermal, batteryCelsius(), rssMb("VmHWM")),
        )
    }

    private fun environment(): String =
        "thermalStatus=${power.currentThermalStatus} headroom10s=%.3f batteryC=%.1f".format(
            power.getThermalHeadroom(10), batteryCelsius(),
        )

    private fun batteryCelsius(): Double {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    }

    /** A field of /proc/self/status in MB: VmHWM is the peak resident set, VmRSS the current one. */
    /** Whether the run kept the foreground: a background process is limited to CPUs 0-2. */
    private fun processState(): String {
        val state = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        val power = context.getSystemService(PowerManager::class.java)
        val cpuset = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("?")
        return "importance=${state.importance} interactive=${power.isInteractive} cpuset=$cpuset"
    }

    private fun rssMb(field: String): Long =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("$field:") }
            ?.filter { it.isDigit() }?.toLongOrNull()?.div(1024) ?: -1

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb))
    }

    private fun stats(xs: List<Double>): String {
        val s = xs.sorted()
        fun p(q: Double) = s[((s.size - 1) * q).toInt()]
        return "mean=%.1f p50=%.1f p95=%.1f min=%.1f max=%.1f n=%d ms".format(xs.average(), p(0.5), p(0.95), s.first(), s.last(), xs.size)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        // litert-community/embeddinggemma-2-text-270m-litert-lm at revision 9be6e8b9.
        const val GENERIC_FILE = "embeddinggemma-2-text-270m.litertlm"
        const val GENERIC_SHA256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"
        const val NPU_FILE = "embeddinggemma-2-text-270m_Google_Tensor_G5.litertlm"
        const val NPU_SHA256 = "f066663433a31a8228b46f3271d2714a3b72a341ed4563cbc6d90f2e3dd0162d"

        const val DIMENSIONS = 256
        const val WARMUP = 3
        const val ITERATIONS = 20

        // Synthetic texts, in Spanish on purpose: the captured data will be Spanish.
        const val SHORT = "Cita con la dentista mañana viernes a las 17:15 en la Clínica Sonrisa."
        const val MEDIUM =
            "Laura Méndez: al final la reunión con el equipo de diseño pasa al jueves 14 a las 10:30 en la sala Norte. " +
                "Trae los bocetos del onboarding y la lista de pantallas pendientes. Avísale también a Diego que " +
                "necesitamos su feedback sobre los colores antes del miércoles, porque el cliente quiere revisar la " +
                "propuesta completa el viernes por la mañana y no hay margen para cambios de última hora."
        val LONG = (1..5).joinToString(" ") {
            "Un estudio de la Universidad de Wageningen publicado en octubre de 2026 analizó 40 variedades de col " +
                "fermentada y encontró aumentos de vitamina K2 y una reducción del 30 % en nitratos. Los autores, " +
                "dirigidos por Anneke de Vries, recomiendan fermentar al menos 21 días a 18 grados, controlar la " +
                "salinidad de la salmuera y evitar recipientes metálicos durante todo el proceso (parte $it)."
        }
        const val QUERY = "¿Cuándo es la reunión con el equipo de diseño?"
        const val RELATED = "La reunión con el equipo de diseño pasa al jueves 14 a las 10:30 en la sala Norte."
        const val UNRELATED = "Tu pedido de auriculares se ha enviado y llegará el martes entre las 9:00 y las 14:00."
    }
}
