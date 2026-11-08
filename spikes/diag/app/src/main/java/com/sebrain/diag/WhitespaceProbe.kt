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
class WhitespaceProbe(private val context: Context, private val set: String = "whitespace") {

    private class Variant(
        val name: String,
        val system: String,
        val keys: List<String>,
        val guidance: JSONObject?,
    )

    private class Sample(
        val valid: Boolean,
        val rawValid: Boolean,
        val schemaOk: Boolean,
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
        val format = v.guidance?.let { ResponseFormat.json(schema(v)) }
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
            "validJson=%d rawValidJson=%d schemaOk=%d usefulTitle=%d loops=%d emptyTitle=%d junkTitle=%d promptTokensAvg=%.1f decodeTokensAvg=%.1f createMsAvg=%.0f totalMsAvg=%.0f totalMsMax=%.0f".format(
                samples.count { it.valid }, samples.count { it.rawValid }, samples.count { it.schemaOk },
                samples.count { it.valid && !it.emptyTitle && !it.junkTitle },
                samples.count { it.loop }, samples.count { it.emptyTitle },
                samples.count { it.junkTitle }, samples.map { it.promptTokens }.average(),
                samples.map { it.decodeTokens }.average(), samples.map { it.createMs }.average(),
                samples.map { it.totalMs }.average(), samples.maxOf { it.totalMs },
            ),
        )
    }

    private fun runSegment(engine: Engine, v: Variant, format: ResponseFormat?, i: Int): Sample {
        val start = System.nanoTime()
        val (conversation, createMs) = timedMs { newConversation(engine, v) }
        return conversation.use { c ->
            val text = CognitionBenchWorker.sample(i)
            val reply = if (format != null) c.sendMessage(text, responseFormat = format) else c.sendMessage(text)
            val out = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            val totalMs = (System.nanoTime() - start) / 1_000_000.0
            val info = c.getBenchmarkInfo()
            val rawJson = runCatching { JSONObject(out) }.getOrNull()
            val json = rawJson ?: runCatching { JSONObject(extractObject(out)) }.getOrNull()
            val title = json?.optString(TITLE_KEYS.first { json.has(it) || it == TITLE_KEYS.last() }).orEmpty()
            val stripped = title.trim { it.isWhitespace() || it in JUNK }
            val sample = Sample(
                valid = json != null,
                rawValid = rawJson != null,
                schemaOk = json != null && matchesSchema(json, v.keys),
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
                "whitespace ${v.name} seg $i valid=${sample.valid} rawValid=${sample.rawValid} schemaOk=${sample.schemaOk} loop=${sample.loop} emptyTitle=${sample.emptyTitle} " +
                    "junkTitle=${sample.junkTitle} promptTokens=${sample.promptTokens} decodeTokens=${sample.decodeTokens} " +
                    "createMs=%.0f totalMs=%.0f output=${visible.take(900)}".format(createMs, totalMs),
            )
            sample
        }
    }

    private fun newConversation(engine: Engine, v: Variant): Conversation = engine.createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(v.system),
            maxOutputToken = MAX_OUTPUT_TOKENS,
            thinkingConfig = ThinkingConfig(false),
            enableResponseFormat = v.guidance != null,
        ),
    )

    /** Text between the first '{' and the last '}', to tolerate Markdown fences around the JSON. */
    private fun extractObject(s: String): String = s.substring(s.indexOf('{'), s.lastIndexOf('}') + 1)

    private fun matchesSchema(json: JSONObject, keys: List<String>): Boolean = keys.all { key ->
        if (key in TITLE_KEYS) {
            json.opt(key) is String
        } else {
            val list = json.opt(key) as? JSONArray
            list != null && (0 until list.length()).all { list.opt(it) is String }
        }
    }

    private fun schema(v: Variant): String {
        val list = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
        val properties = JSONObject()
        v.keys.forEach { key ->
            properties.put(key, if (key in TITLE_KEYS) JSONObject().put("type", "string") else list)
        }
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
        if (set == "title") {
            return listOf(
                Variant("en title last whitespace_flexible=false", TITLE_LAST_SYSTEM, TITLE_LAST_KEYS, compact),
                Variant("en no constrained decoding", CognitionBenchWorker.SYSTEM, ENGLISH_KEYS, null),
            )
        }
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
        val TITLE_KEYS = listOf("title", "titulo")
        val ENGLISH_KEYS = listOf("title", "people", "dates", "topics", "tasks")
        val TITLE_LAST_KEYS = listOf("people", "dates", "topics", "tasks", "title")
        const val TITLE_LAST_SYSTEM =
            "Extract structured information from the user's text. Respond only with valid JSON " +
                "that follows the schema: people, dates (ISO 8601 if possible), topics, pending tasks " +
                "and title (short phrase). Use empty lists if there is no data. " +
                "Keep values in the original language of the text."

        // Deliberate exception to the English rule: this is the measured baseline being compared against.
        const val SPANISH_BASELINE_SYSTEM =
            "Extrae información estructurada del texto del usuario. Responde solo con JSON válido " +
                "que siga el esquema: titulo (frase corta), personas, fechas (ISO 8601 si es posible), " +
                "temas y tareas pendientes. Listas vacías si no hay datos."
        val SPANISH_BASELINE_KEYS = listOf("titulo", "personas", "fechas", "temas", "tareas")
    }
}
