package com.sebrain.cognition

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What the extraction model is asked and how its answer is judged, as decided in
 * `docs/design-cognition-worker.md` (§3, §4 and §6). No engine here: the caller sends [SYSTEM],
 * [message] and [SCHEMA] to the model and gives the raw reply to [parse].
 */
object Extraction {
    /** Lists the fields in the schema's order, title last (§6: a title first came out as `": "`). */
    const val SYSTEM =
        "Extract structured information from the user's text. Respond only with valid JSON " +
            "that follows the schema: people, dates (ISO 8601 if possible), topics, pending tasks " +
            "and title (short phrase). Use empty lists if there is no data. " +
            "Keep values in the original language of the text. " +
            // ExtractionEvalTest, 2026-11-20: E2B put the capture date in 16 of 27 captures and
            // left out requests such as "bring snacks" in 9 of 19.
            "dates: only the dates the text mentions, as yyyy-MM-dd, looked up in the calendar given; " +
            "never the capture date unless the text means today. " +
            "tasks: everything the text asks or reminds someone to do, as a short phrase."

    /** The minimal schema of §6: compact JSON (no whitespace loop) and the title last. */
    val SCHEMA = """
        {"type":"object","x-guidance":{"whitespace_flexible":false},"properties":{
          "people":{"type":"array","items":{"type":"string"}},
          "dates":{"type":"array","items":{"type":"string"}},
          "topics":{"type":"array","items":{"type":"string"}},
          "tasks":{"type":"array","items":{"type":"string"}},
          "title":{"type":"string"}},
         "required":["people","dates","topics","tasks","title"]}
    """.trimIndent()

    /** Added to the message on the single retry of §4, which must not repeat the same request. */
    const val RETRY_NOTE =
        "The previous response was not valid JSON or had an empty title. " +
            "Return only the JSON object, with a title of 3 to 8 words."

    /** Output tokens for one extraction (§4: 512, the same on the retry). */
    const val MAX_TOKENS = 512

    /**
     * More whitespace than this in a row, outside a string, is the loop of §4: the reply is cut
     * and goes straight to the retry instead of running for half a minute.
     */
    const val WHITESPACE_RUN = 32

    private const val MIN_TITLE_LETTERS = 2

    /** Days listed after the capture date: two weeks covers "next Friday" and "the 20th" of the same month. */
    private const val CALENDAR_DAYS = 14L
    private val KEYS = listOf("people", "dates", "topics", "tasks")

    /** The extracted fields. Lists are never null; the title is trimmed and not empty. */
    data class Fields(
        val people: List<String>,
        val dates: List<String>,
        val topics: List<String>,
        val tasks: List<String>,
        val title: String,
    )

    /** Why a reply was not accepted; the code is what gets stored, never the reply (§4). */
    enum class Failure(val code: String) {
        JSON_INVALID("json_invalid"),
        TITLE_EMPTY("title_empty"),
        TRUNCATED("truncated"),
    }

    sealed interface Outcome {
        data class Valid(val fields: Fields) : Outcome
        data class Invalid(val failure: Failure) : Outcome
    }

    /**
     * The user message: the capture date as the reference for relative dates (§3; the spike
     * invented a year for "el jueves 14"), then the text, and on a retry the corrective note.
     */
    fun message(text: String, capturedAt: Instant, timeZone: ZoneId, retry: Boolean = false): String {
        val local = capturedAt.atZone(timeZone)
        val day = local.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return buildString {
            append("Capture date and time: ").append(local.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append(" (").append(day).append(").\n")
            append("Resolve relative dates (\"tomorrow\", \"on Thursday\", \"the 14th\") against that date. ")
            append("If the text does not give the year, use the capture date's year, or the next one if the date has already passed. ")
            append("If a date cannot be resolved, keep it as literal text instead of inventing it.\n")
            // A small model does not do calendar arithmetic well; it can look a day up.
            append("Calendar: ")
            append((1..CALENDAR_DAYS).joinToString(", ") { n ->
                val date = local.toLocalDate().plusDays(n)
                "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $date"
            })
            append(".\n\n")
            append(text)
            if (retry) append("\n\n").append(RETRY_NOTE)
        }
    }

    /** What the model wrote for one attempt; [cut] is true when the whitespace guard stopped it. */
    class Reply(val text: String, val cut: Boolean)

    /** The result of [extract]: the last attempt's outcome and how many attempts it took (1 or 2). */
    data class Result(val outcome: Outcome, val attempts: Int)

    /**
     * One capture, by §4: a first attempt and, if it is not valid, a single retry with the
     * corrective note. [send] runs the model on a user message. An engine error is thrown by
     * [send] and is not an extraction failure: the caller keeps the capture pending.
     */
    fun extract(text: String, capturedAt: Instant, timeZone: ZoneId, send: (String) -> Reply): Result {
        val first = send(message(text, capturedAt, timeZone)).let { parse(it.text, it.cut) }
        if (first is Outcome.Valid) return Result(first, 1)
        val second = send(message(text, capturedAt, timeZone, retry = true)).let { parse(it.text, it.cut) }
        return Result(second, 2)
    }

    /** Judges a raw reply by the rules of §4. [cut] is true when the reply was stopped early. */
    fun parse(reply: String, cut: Boolean = false): Outcome {
        if (cut) return Outcome.Invalid(Failure.TRUNCATED)
        val json = try {
            JSONObject(reply.trim())
        } catch (e: JSONException) {
            return Outcome.Invalid(if (looksUnfinished(reply)) Failure.TRUNCATED else Failure.JSON_INVALID)
        }
        val lists = KEYS.map { key -> strings(json.opt(key)) ?: return Outcome.Invalid(Failure.JSON_INVALID) }
        val rawTitle = json.opt("title") as? String ?: return Outcome.Invalid(Failure.JSON_INVALID)
        val title = cleanTitle(rawTitle)
        if (title.count { it.isLetter() } < MIN_TITLE_LETTERS) return Outcome.Invalid(Failure.TITLE_EMPTY)
        return Outcome.Valid(Fields(lists[0], lists[1], lists[2], lists[3], title))
    }

    /** True once a streamed reply has run into the whitespace loop. */
    fun inWhitespaceLoop(streamed: CharSequence): Boolean {
        var run = 0
        var inString = false
        var escaped = false
        for (c in streamed) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
            }
            run = if (!inString && c.isWhitespace()) run + 1 else 0
            if (run > WHITESPACE_RUN) return true
        }
        return false
    }

    /** A list of strings, blank items dropped; null if the value is not a list of strings. */
    private fun strings(value: Any?): List<String>? {
        val array = value as? JSONArray ?: return null
        return (0 until array.length()).map { i -> array.opt(i) as? String ?: return null }
            .map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** `": "`, quotes and stray colons around a title are what the spike's bad titles were made of. */
    private fun cleanTitle(title: String): String = title.trim { it.isWhitespace() || it == '"' || it == ':' || it == '\'' }

    /** A reply that opens an object and never closes it ran out of tokens. */
    private fun looksUnfinished(reply: String): Boolean {
        val trimmed = reply.trim()
        return trimmed.startsWith("{") && !trimmed.endsWith("}")
    }
}
