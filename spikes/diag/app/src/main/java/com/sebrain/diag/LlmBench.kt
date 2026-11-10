package com.sebrain.diag

import android.content.Context
import android.os.PowerManager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Compares candidate LLM files on one backend per process: load time, speed, memory and the
 * extraction task. Start with `--es mode llm --es model <key> --es backend gpu|cpu|npu`.
 */
@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
class LlmBench(private val context: Context, private val modelKey: String, private val backendName: String) {
    private class Model(val file: String, val sha256: String)

    private val label = "Llm $modelKey $backendName"

    fun run() {
        Diag.info("$label start: ${processState()}")
        try {
            bench()
        } catch (t: Throwable) {
            Diag.error(label, t)
            Diag.result(label, "FAIL", "-", Diag.describe(t))
        }
        Diag.info("$label end: ${processState()}")
        Diag.info("LLM_DONE $modelKey $backendName")
    }

    private fun bench() {
        val model = MODELS[modelKey] ?: error("Unknown model $modelKey")
        val file = File(context.filesDir, "models/${model.file}")
        check(file.exists()) { "Model missing at ${file.path}" }
        check(sha256(file) == model.sha256) { "SHA-256 mismatch for ${model.file}" }
        val backend = when (backendName) {
            "npu" -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
            "gpu" -> Backend.GPU()
            "cpu" -> Backend.CPU()
            else -> error("Unknown backend $backendName")
        }
        val power = context.getSystemService(PowerManager::class.java)

        // Must be set before the Engine is created.
        ExperimentalFlags.enableBenchmark = true
        val (engine, loadMs) = timedMs {
            Engine(EngineConfig(modelPath = file.path, backend = backend, cacheDir = context.cacheDir.path))
                .also { it.initialize() }
        }
        Diag.result("$label engine load", "PASS", "%.0f ms".format(loadMs), "${model.file}; rss=${rssMb("VmRSS")} MB")

        engine.use {
            // Free generation: decode speed without constrained decoding.
            engine.createConversation(
                ConversationConfig(maxOutputToken = 256, thinkingConfig = ThinkingConfig(false)),
            ).use { conversation ->
                val (reply, ms) = timedMs { text(conversation.sendMessage(FREE_PROMPT)) }
                val info = conversation.getBenchmarkInfo()
                Diag.result(
                    "$label free generation", "PASS", "%.1f tok/s decode".format(info.lastDecodeTokensPerSecond),
                    "ttft=%.0f ms; prefill=%d tok at %.1f tok/s; decode=%d tok; total=%.0f ms; chars=%d".format(
                        info.timeToFirstTokenInSecond * 1000, info.lastPrefillTokenCount,
                        info.lastPrefillTokensPerSecond, info.lastDecodeTokenCount, ms, reply.length,
                    ),
                )
                Diag.info("$label free output: ${reply.replace('\n', ' ').take(500)}")
            }

            // The project's extraction task, with the minimal schema.
            var valid = 0
            var emptyTitle = 0
            val totals = ArrayList<Double>()
            val decodeSpeeds = ArrayList<Double>()
            for (i in 0 until SEGMENTS) {
                val start = System.nanoTime()
                val config = ConversationConfig(
                    systemInstruction = Contents.of(CognitionBenchWorker.EXTRACTION_SYSTEM),
                    maxOutputToken = 512,
                    thinkingConfig = ThinkingConfig(false),
                    enableResponseFormat = true,
                )
                engine.createConversation(config).use { conversation ->
                    val out = text(
                        conversation.sendMessage(
                            CognitionBenchWorker.sample(i),
                            responseFormat = ResponseFormat.json(CognitionBenchWorker.SCHEMA),
                        ),
                    )
                    totals += (System.nanoTime() - start) / 1_000_000.0
                    decodeSpeeds += conversation.getBenchmarkInfo().lastDecodeTokensPerSecond
                    val json = runCatching { JSONObject(out) }.getOrNull()
                    if (json != null) {
                        valid++
                        if (json.optString("title").trim().isEmpty()) emptyTitle++
                    }
                    if (i == 0) Diag.info("$label extraction output seg 0: ${out.take(400)}")
                }
            }
            Diag.result(
                "$label extraction", if (valid == SEGMENTS) "PASS" else "FAIL", "%.0f ms/segment".format(totals.average()),
                "valid JSON %d/%d; empty title %d; min=%.0f max=%.0f ms; decode %.1f tok/s".format(
                    valid, SEGMENTS, emptyTitle, totals.min(), totals.max(), decodeSpeeds.average(),
                ),
            )
        }
        Diag.result(
            "$label memory", "INFO", "peak ${rssMb("VmHWM")} MB",
            "thermalStatus=${power.currentThermalStatus} headroom10s=%.3f".format(power.getThermalHeadroom(10)),
        )
    }

    private fun text(message: com.google.ai.edge.litertlm.Message): String =
        message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

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
        const val SEGMENTS = 5
        const val FREE_PROMPT = "Explica en un párrafo de unas cien palabras para qué sirve llevar un diario personal."

        /** Third-party community builds. Spikes only; see docs/benchmarks.md. */
        val MODELS = mapOf(
            // olekk/gemma-4-E4B-it-abliterated-litert-lm at revision a4eecccd.
            "e4b-abliterated" to Model(
                "gemma-4-E4B-it-abliterated.litertlm",
                "f5a6625e0368e853743c91b39fa6ac6a8add1fc4825b18f98f518966610f2a88",
            ),
            // PeppX/gemma-4-e2b-uncensored-litertlm at revision 0adcc4e5.
            "e2b-uncensored" to Model(
                "gemma-4-E2B-it-Uncensored-MAX.litertlm",
                "34738f7eb50d04ee123d23dcea0df0b49e074b555134101251685f9416f39bb9",
            ),
        )
    }
}
