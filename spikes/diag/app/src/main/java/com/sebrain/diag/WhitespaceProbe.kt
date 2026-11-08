package com.sebrain.diag

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compares whitespace handling and prompt language on the extraction task.
 * Start with `--es mode whitespace --ez show true`. Every variant sees the same 15 segments:
 * the 10 control segments (0-9) and the 5 that looped (29, 33, 43, 68, 83).
 */
@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
class WhitespaceProbe(private val context: Context) {

    private class Variant(
        val name: String,
        val system: String,
        val keys: List<String>,
        val guidance: JSONObject,
    )

    private class Sample(
        val valid: Boolean,
        val loop: Boolean,
        val emptyTitle: Boolean,
        val junkTitle: Boolean,
        val promptTokens: Int,
        val decodeTokens: Int,
        val createMs: Double,
        val totalMs: Double,
    )

    fun run() {
        // Must be set before the Engine is created.
        ExperimentalFlags.enableBenchmark = true
        val model = File(context.filesDir, "models/${CognitionBenchWorker.MODEL_FILE}")
        val engine = try {
            Engine(
                EngineConfig(
                    modelPath = model.path,
                    backend = Backend.NPU(context.applicationInfo.nativeLibraryDir),
                    cacheDir = context.cacheDir.path,
                ),
            ).also { it.initialize() }
        } catch (t: Throwable) {
            Diag.error("whitespace engine load", t)
            return
        }
        engine.use { variants().forEach { runVariant(engine, it) } }
        Diag.info("whitespace END")
    }

    private fun runVariant(engine: Engine, v: Variant) {
        val samples = ArrayList<Sample>(SEGMENTS.size)
        val format = ResponseFormat.json(schema(v))
        for (i in SEGMENTS) {
            try {
                samples += runSegment(engine, v, format, i)
            } catch (t: Throwable) {
                Diag.error("whitespace ${v.name} seg $i", t)
            }
        }
        if (samples.isEmpty()) {
            Diag.result("whitespace ${v.name}", "FAIL", "-", "no segment completed")
            return
        }
        Diag.result(
            "whitespace ${v.name}", "INFO", "n=${samples.size}",
            "validJson=%d loops=%d emptyTitle=%d junkTitle=%d promptTokensAvg=%.1f decodeTokensAvg=%.1f createMsAvg=%.0f totalMsAvg=%.0f totalMsMax=%.0f".format(
                samples.count { it.valid }, samples.count { it.loop }, samples.count { it.emptyTitle },
                samples.count { it.junkTitle }, samples.map { it.promptTokens }.average(),
                samples.map { it.decodeTokens }.average(), samples.map { it.createMs }.average(),
                samples.map { it.totalMs }.average(), samples.maxOf { it.totalMs },
            ),
        )
    }

    private fun runSegment(engine: Engine, v: Variant, format: ResponseFormat, i: Int): Sample {
        val start = System.nanoTime()
        val (conversation, createMs) = timedMs { newConversation(engine, v) }
        return conversation.use { c ->
            val out = c.sendMessage(CognitionBenchWorker.sample(i), responseFormat = format)
                .contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            val totalMs = (System.nanoTime() - start) / 1_000_000.0
            val info = c.getBenchmarkInfo()
            val json = runCatching { JSONObject(out) }.getOrNull()
            val title = json?.optString(v.keys.first()).orEmpty()
            val stripped = title.trim { it.isWhitespace() || it in JUNK }
            val sample = Sample(
                valid = json != null,
                loop = longestWhitespaceRun(out) > LOOP_THRESHOLD,
                emptyTitle = json != null && stripped.isEmpty(),
                junkTitle = json != null && stripped.isNotEmpty() && (title.firstOrNull()?.let { it in JUNK } == true),
                promptTokens = info.lastPrefillTokenCount,
                decodeTokens = info.lastDecodeTokenCount,
                createMs = createMs,
                totalMs = totalMs,
            )
            // Newlines and tabs are made visible; replacing them with spaces hid the real loop before.
            val visible = out.replace("\n", "⏎").replace("\t", "⇥")
            Diag.info(
                "whitespace ${v.name} seg $i valid=${sample.valid} loop=${sample.loop} emptyTitle=${sample.emptyTitle} " +
                    "junkTitle=${sample.junkTitle} promptTokens=${sample.promptTokens} decodeTokens=${sample.decodeTokens} " +
                    "createMs=%.0f totalMs=%.0f output=${visible.take(600)}".format(createMs, totalMs),
            )
            sample
        }
    }

    private fun newConversation(engine: Engine, v: Variant): Conversation = engine.createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(v.system),
            maxOutputToken = MAX_OUTPUT_TOKENS,
            thinkingConfig = ThinkingConfig(false),
            enableResponseFormat = true,
        ),
    )

    private fun schema(v: Variant): String {
        val list = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
        val properties = JSONObject().put(v.keys.first(), JSONObject().put("type", "string"))
        v.keys.drop(1).forEach { properties.put(it, list) }
        return JSONObject()
            .put("type", "object")
            .put("x-guidance", v.guidance)
            .put("properties", properties)
            .put("required", JSONArray(v.keys))
            .toString()
    }

    /** Longest run of JSON whitespace outside string literals. */
    private fun longestWhitespaceRun(s: String): Int {
        var inString = false
        var escaped = false
        var run = 0
        var longest = 0
        for (c in s) {
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                run = 0
            } else if (c == '"') {
                inString = true
                run = 0
            } else if (c.isWhitespace()) {
                run++
                if (run > longest) longest = run
            } else {
                run = 0
            }
        }
        return longest
    }

    private fun variants(): List<Variant> {
        val compact = JSONObject().put("whitespace_flexible", false)
        val singleSpace = JSONObject().put("whitespace_pattern", "\\x20")
        return listOf(
            Variant("es whitespace_flexible=false", SPANISH_BASELINE_SYSTEM, SPANISH_BASELINE_KEYS, compact),
            Variant("en whitespace_flexible=false", CognitionBenchWorker.SYSTEM, ENGLISH_KEYS, compact),
            Variant("en whitespace_pattern=x20", CognitionBenchWorker.SYSTEM, ENGLISH_KEYS, singleSpace),
        )
    }

    private companion object {
        const val MAX_OUTPUT_TOKENS = 512
        const val LOOP_THRESHOLD = 32
        const val JUNK = ":,>\"[{"
        val SEGMENTS = (0..9).toList() + listOf(29, 33, 43, 68, 83)
        val ENGLISH_KEYS = listOf("title", "people", "dates", "topics", "tasks")

        // Deliberate exception to the English rule: this is the measured baseline being compared against.
        const val SPANISH_BASELINE_SYSTEM =
            "Extrae información estructurada del texto del usuario. Responde solo con JSON válido " +
                "que siga el esquema: titulo (frase corta), personas, fechas (ISO 8601 si es posible), " +
                "temas y tareas pendientes. Listas vacías si no hay datos."
        val SPANISH_BASELINE_KEYS = listOf("titulo", "personas", "fechas", "temas", "tareas")
    }
}
