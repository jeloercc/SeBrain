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
 * Verifica qué restricciones del esquema JSON respeta LiteRT-LM 0.18.1 (llguidance).
 * Lanzar con `--es mode schema`. Usa los textos de los segmentos que fallaban (29, 33, 43, 68, 83)
 * y sondas diseñadas para provocar cada violación.
 */
class SchemaProbe(private val context: Context) {

    private class Probe(
        val name: String,
        val segments: List<Int>,
        val format: () -> ResponseFormat,
        val extraSystem: String = "",
        val check: (String, JSONObject?) -> String?, // null = cumple; texto = violación
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
            Diag.error("schema carga del motor", t); return
        }
        engine.use { probes().forEach { runProbe(engine, it) } }
        Diag.info("schema FIN")
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
            Diag.info("schema ${p.name} seg $i %.0f ms salida=${out.replace('\n', '⏎').replace('\t', '⇥').take(700)}".format(ms))
        }
        Diag.result(
            "schema ${p.name}", if (ok == p.segments.size) "RESPETA" else "NO RESPETA",
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
            Probe("control esquema actual", failing, { ResponseFormat.json(BASE) }, check = ::basicValid),
            Probe("x-guidance whitespace_flexible=false", failing, { ResponseFormat.json(schema(xg = true)) }) { out, j ->
                basicValid(out, j) ?: whitespaceOutsideStrings(out)?.let { "whitespace fuera de strings: $it" }
            },
            Probe("esquema endurecido completo", failing, { ResponseFormat.json(schema(xg = true, hard = true)) }) { out, j ->
                basicValid(out, j) ?: titleRule(j!!.optString("titulo"), 3, 80, Regex("^[^\\s:\"]"))
                    ?: whitespaceOutsideStrings(out)?.let { "whitespace fuera de strings: $it" }
            },
            Probe("minLength=40 en titulo", listOf(0, 3, 7), { ResponseFormat.json(schema(titulo = """{"type":"string","minLength":40}""")) }) { out, j ->
                basicValid(out, j) ?: j!!.optString("titulo").let { if (it.length < 40) "len=${it.length}" else null }
            },
            Probe("maxLength=10 en titulo", listOf(0, 3, 7), { ResponseFormat.json(schema(titulo = """{"type":"string","maxLength":10}""")) }) { out, j ->
                basicValid(out, j) ?: j!!.optString("titulo").let { if (it.length > 10) "len=${it.length}" else null }
            },
            Probe("pattern ^ZZ en titulo", listOf(0, 3, 7), { ResponseFormat.json(schema(titulo = """{"type":"string","pattern":"^ZZ"}""")) }) { out, j ->
                basicValid(out, j) ?: j!!.optString("titulo").let { if (!it.startsWith("ZZ")) "titulo=\"${it.take(40)}\"" else null }
            },
            Probe("maxItems=1 en personas", listOf(0, 4, 5), { ResponseFormat.json(schema(personasMax = 1)) }) { out, j ->
                basicValid(out, j) ?: j!!.optJSONArray("personas")?.length()?.let { if (it > 1) "personas=$it" else null }
            },
            Probe(
                "additionalProperties=false", listOf(0, 3, 4), { ResponseFormat.json(schema(additional = false)) },
                extraSystem = " Añade siempre además un campo \"lugar\" con el sitio mencionado.",
            ) { out, j -> basicValid(out, j) ?: if (j!!.has("lugar")) "apareció \"lugar\"" else null },
            Probe(
                "control additionalProperties ausente", listOf(0, 3, 4), { ResponseFormat.json(BASE) },
                extraSystem = " Añade siempre además un campo \"lugar\" con el sitio mencionado.",
            ) { out, j -> basicValid(out, j) ?: if (j!!.has("lugar")) "apareció \"lugar\" (esperado sin restricción)" else null },
            Probe("regex JSON compacto", failing, { ResponseFormat.regex(COMPACT_REGEX) }) { out, j ->
                basicValid(out, j) ?: titleRule(j!!.optString("titulo"), 3, 80, Regex("^[^\\s:\"]"))
                    ?: whitespaceOutsideStrings(out)?.let { "whitespace fuera de strings: $it" }
            },
        )
    }

    private fun basicValid(out: String, j: JSONObject?): String? =
        if (j == null) "JSON inválido (${out.length} chars)" else null

    private fun titleRule(t: String, min: Int, max: Int, re: Regex): String? = when {
        t.length < min -> "titulo corto (${t.length}): \"$t\""
        t.length > max -> "titulo largo (${t.length})"
        !re.containsMatchIn(t) -> "titulo no cumple pattern: \"${t.take(40)}\""
        else -> null
    }

    /** Cuenta caracteres de espacio en blanco fuera de strings JSON. */
    private fun whitespaceOutsideStrings(s: String): Int? {
        var inStr = false; var esc = false; var n = 0
        for (c in s) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false }
            else if (c == '"') inStr = true else if (c.isWhitespace()) n++
        }
        return if (n == 0) null else n
    }

    companion object {
        private const val SYSTEM =
            "Extrae información estructurada del texto del usuario. Responde solo con JSON válido " +
                "que siga el esquema: titulo (frase corta), personas, fechas (ISO 8601 si es posible), " +
                "temas y tareas pendientes. Listas vacías si no hay datos."

        private val BASE = schema()

        private fun arr(max: Int?) =
            """{"type":"array","items":{"type":"string"${if (max != null) ""","minLength":1""" else ""}}${if (max != null) ""","maxItems":$max""" else ""}}"""

        fun schema(
            xg: Boolean = false,
            hard: Boolean = false,
            titulo: String? = null,
            personasMax: Int? = null,
            additional: Boolean? = null,
        ): String {
            val t = titulo ?: if (hard) """{"type":"string","minLength":3,"maxLength":80,"pattern":"^[^\\s:\"]"}""" else """{"type":"string"}"""
            val obj = JSONObject()
            obj.put("type", "object")
            if (xg) obj.put("x-guidance", JSONObject().put("whitespace_flexible", false))
            if (hard || additional == false) obj.put("additionalProperties", false)
            obj.put(
                "properties",
                JSONObject()
                    .put("titulo", JSONObject(t))
                    .put("personas", JSONObject(arr(personasMax ?: if (hard) 10 else null)))
                    .put("fechas", JSONObject(arr(if (hard) 10 else null)))
                    .put("temas", JSONObject(arr(if (hard) 8 else null)))
                    .put("tareas", JSONObject(arr(if (hard) 10 else null))),
            )
            obj.put("required", JSONArray(listOf("titulo", "personas", "fechas", "temas", "tareas")))
            return obj.toString()
        }

        private const val S = "\"(?:[^\"\\\\\\x00-\\x1f]|\\\\[\"\\\\/bfnrt]){1,120}\""
        private const val LIST = "\\[(?:$S(?:,$S){0,9})?\\]"
        private const val COMPACT_REGEX =
            "\\{\"titulo\":\"[^\\s:\"\\\\][^\"\\\\\\x00-\\x1f]{2,79}\"," +
                "\"personas\":$LIST,\"fechas\":$LIST,\"temas\":$LIST,\"tareas\":$LIST\\}"
    }
}
