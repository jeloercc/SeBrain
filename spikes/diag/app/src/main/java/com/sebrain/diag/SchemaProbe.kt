package com.sebrain.diag

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Checks which JSON schema constraints LiteRT-LM 0.18.1 (llguidance) respects.
 * Start with `--es mode schema`. Uses the texts of the segments that failed (29, 33, 43, 68, 83)
 * and probes designed to provoke each violation.
 */
class SchemaProbe(private val context: Context) {

    private class Probe(
        val name: String,
        val segments: List<Int>,
        val format: () -> ResponseFormat,
        val extraSystem: String = "",
        val check: (String, JSONObject?) -> String?, // null = satisfied; text = violation
    )

    fun run() {
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
            Diag.error("schema engine load", t); return
        }
        engine.use { probes().forEach { runProbe(engine, it) } }
        Diag.info("schema END")
    }

    private fun runProbe(engine: Engine, p: Probe) {
        var ok = 0
        val notes = mutableListOf<String>()
        for (i in p.segments) {
            val text = CognitionBenchWorker.sample(i)
            val (out, ms) = try {
                timedMs { generate(engine, p, text) }
            } catch (t: Throwable) {
                Diag.error("schema ${p.name} seg $i", t)
                notes += "seg $i ERROR ${Diag.describe(t).take(200)}"
                continue
            }
            val json = runCatching { JSONObject(out) }.getOrNull()
            val violation = p.check(out, json)
            if (violation == null) ok++ else notes += "seg $i: $violation"
            Diag.info("schema ${p.name} seg $i %.0f ms output=${out.replace('\n', '⏎').replace('\t', '⇥').take(700)}".format(ms))
        }
        Diag.result(
            "schema ${p.name}", if (ok == p.segments.size) "RESPECTED" else "NOT RESPECTED",
            "$ok/${p.segments.size}", notes.joinToString("; ").replace('|', '/'),
        )
    }

    private fun generate(engine: Engine, p: Probe, text: String): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM + p.extraSystem),
            maxOutputToken = 512,
            thinkingConfig = ThinkingConfig(false),
            enableResponseFormat = true,
        )
        return engine.createConversation(config).use { c ->
            c.sendMessage(text, responseFormat = p.format())
                .contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }

    private fun probes(): List<Probe> {
        val failing = listOf(29, 33, 43, 68, 83)
        return listOf(
            Probe("control current schema", failing, { ResponseFormat.json(BASE) }, check = ::basicValid),
            Probe("x-guidance whitespace_flexible=false", failing, { ResponseFormat.json(schema(xg = true)) }) { out, j ->
                basicValid(out, j) ?: whitespaceOutsideStrings(out)?.let { "whitespace outside strings: $it" }
            },
            Probe("full hardened schema", failing, { ResponseFormat.json(schema(xg = true, hard = true)) }) { out, j ->
                basicValid(out, j) ?: titleRule(j!!.optString("title"), 3, 80, Regex("^[^\\s:\"]"))
                    ?: whitespaceOutsideStrings(out)?.let { "whitespace outside strings: $it" }
            },
            Probe("minLength=40 on title", listOf(0, 3, 7), { ResponseFormat.json(schema(title = """{"type":"string","minLength":40}""")) }) { out, j ->
                basicValid(out, j) ?: j!!.optString("title").let { if (it.length < 40) "len=${it.length}" else null }
            },
            Probe("maxLength=10 on title", listOf(0, 3, 7), { ResponseFormat.json(schema(title = """{"type":"string","maxLength":10}""")) }) { out, j ->
                basicValid(out, j) ?: j!!.optString("title").let { if (it.length > 10) "len=${it.length}" else null }
            },
            Probe("pattern ^ZZ on title", listOf(0, 3, 7), { ResponseFormat.json(schema(title = """{"type":"string","pattern":"^ZZ"}""")) }) { out, j ->
                basicValid(out, j) ?: j!!.optString("title").let { if (!it.startsWith("ZZ")) "title=\"${it.take(40)}\"" else null }
            },
            Probe("maxItems=1 on people", listOf(0, 4, 5), { ResponseFormat.json(schema(peopleMax = 1)) }) { out, j ->
                basicValid(out, j) ?: j!!.optJSONArray("people")?.length()?.let { if (it > 1) "people=$it" else null }
            },
            Probe(
                "additionalProperties=false", listOf(0, 3, 4), { ResponseFormat.json(schema(additional = false)) },
                extraSystem = " Always also add a \"place\" field with the location mentioned.",
            ) { out, j -> basicValid(out, j) ?: if (j!!.has("place")) "\"place\" appeared" else null },
            Probe(
                "control additionalProperties absent", listOf(0, 3, 4), { ResponseFormat.json(BASE) },
                extraSystem = " Always also add a \"place\" field with the location mentioned.",
            ) { out, j -> basicValid(out, j) ?: if (j!!.has("place")) "\"place\" appeared (expected without the constraint)" else null },
            Probe("regex compact JSON", failing, { ResponseFormat.regex(COMPACT_REGEX) }) { out, j ->
                basicValid(out, j) ?: titleRule(j!!.optString("title"), 3, 80, Regex("^[^\\s:\"]"))
                    ?: whitespaceOutsideStrings(out)?.let { "whitespace outside strings: $it" }
            },
        )
    }

    private fun basicValid(out: String, j: JSONObject?): String? =
        if (j == null) "invalid JSON (${out.length} chars)" else null

    private fun titleRule(t: String, min: Int, max: Int, re: Regex): String? = when {
        t.length < min -> "short title (${t.length}): \"$t\""
        t.length > max -> "long title (${t.length})"
        !re.containsMatchIn(t) -> "title does not match pattern: \"${t.take(40)}\""
        else -> null
    }

    /** Counts whitespace characters outside JSON strings. */
    private fun whitespaceOutsideStrings(s: String): Int? {
        var inStr = false; var esc = false; var n = 0
        for (c in s) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false }
            else if (c == '"') inStr = true else if (c.isWhitespace()) n++
        }
        return if (n == 0) null else n
    }

    companion object {
        private const val SYSTEM = CognitionBenchWorker.SYSTEM

        private val BASE = schema()

        private fun arr(max: Int?) =
            """{"type":"array","items":{"type":"string"${if (max != null) ""","minLength":1""" else ""}}${if (max != null) ""","maxItems":$max""" else ""}}"""

        fun schema(
            xg: Boolean = false,
            hard: Boolean = false,
            title: String? = null,
            peopleMax: Int? = null,
            additional: Boolean? = null,
        ): String {
            val t = title ?: if (hard) """{"type":"string","minLength":3,"maxLength":80,"pattern":"^[^\\s:\"]"}""" else """{"type":"string"}"""
            val obj = JSONObject()
            obj.put("type", "object")
            if (xg) obj.put("x-guidance", JSONObject().put("whitespace_flexible", false))
            if (hard || additional == false) obj.put("additionalProperties", false)
            obj.put(
                "properties",
                JSONObject()
                    .put("title", JSONObject(t))
                    .put("people", JSONObject(arr(peopleMax ?: if (hard) 10 else null)))
                    .put("dates", JSONObject(arr(if (hard) 10 else null)))
                    .put("topics", JSONObject(arr(if (hard) 8 else null)))
                    .put("tasks", JSONObject(arr(if (hard) 10 else null))),
            )
            obj.put("required", JSONArray(listOf("title", "people", "dates", "topics", "tasks")))
            return obj.toString()
        }

        private const val S = "\"(?:[^\"\\\\\\x00-\\x1f]|\\\\[\"\\\\/bfnrt]){1,120}\""
        private const val LIST = "\\[(?:$S(?:,$S){0,9})?\\]"
        private const val COMPACT_REGEX =
            "\\{\"title\":\"[^\\s:\"\\\\][^\"\\\\\\x00-\\x1f]{2,79}\"," +
                "\"people\":$LIST,\"dates\":$LIST,\"topics\":$LIST,\"tasks\":$LIST\\}"
    }
}
