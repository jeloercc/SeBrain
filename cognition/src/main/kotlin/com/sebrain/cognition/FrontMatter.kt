package com.sebrain.cognition

import java.time.LocalDate

/**
 * Reads the YAML front matter of a note, the block between two `---` lines at the very top
 * that Obsidian calls properties.
 *
 * Only the part of YAML that properties use is understood: `key: value`, quoted strings,
 * inline lists `[a, b]` and block lists (`- item` lines). Anything else, such as a nested map
 * or a multi-line string, is left in the note's text and not indexed. A block that cannot be
 * read is never dropped: the note keeps its text and [Parsed.problem] says what failed.
 */
object FrontMatter {
    enum class Type { TEXT, NUMBER, DATE, BOOL, LIST }

    /**
     * One property. A list has one entry in [values] per item; the others have one. [numbers]
     * runs parallel to [values]: the number itself, a date as its epoch day, a boolean as 0 or
     * 1, and null for text, so properties can be compared and sorted.
     */
    data class Property(val key: String, val type: Type, val values: List<String>) {
        val numbers: List<Double?> get() = values.map { if (type == Type.TEXT) null else numberOf(it) }
    }

    class Parsed(
        val properties: List<Property>,
        /** Where the note's own text starts, after the block; 0 if there is no block. */
        val bodyStart: Int,
        val problem: String?,
    ) {
        operator fun get(key: String): Property? = properties.firstOrNull { it.key.equals(key, ignoreCase = true) }

        /** The values of the first of [keys] that is present, for `tags`/`tag` and `aliases`/`alias`. */
        fun valuesOf(vararg keys: String): List<String> = keys.firstNotNullOfOrNull { get(it) }?.values.orEmpty()
    }

    private val NONE = Parsed(emptyList(), 0, null)
    private val KEY_LINE = Regex("^([\\p{L}\\p{N}_][\\p{L}\\p{N}_ .-]*?)\\s*:(?:\\s+(.*)|\\s*)$")
    private val ITEM_LINE = Regex("^\\s*-\\s+(.*)$")
    private val NUMBER = Regex("^-?\\d+(\\.\\d+)?$")
    private val DATE = Regex("^(\\d{4}-\\d{2}-\\d{2})([T ].*)?$")

    fun parse(text: String): Parsed {
        val end = blockEnd(text) ?: return NONE
        // Lines between the opening and the closing marker.
        val lines = text.substring(0, end.first).lines().drop(1)
        val properties = ArrayList<Property>()
        var problem: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            i++
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            // An indented line here belongs to a structure this parser does not index.
            if (line.first().isWhitespace()) continue
            val match = KEY_LINE.find(line.trimEnd())
            if (match == null) {
                problem = problem ?: "Property line ${i + 1} could not be read: ${line.take(40)}"
                continue
            }
            val key = match.groupValues[1].trim()
            val raw = match.groupValues[2].trim()
            when {
                raw.isEmpty() -> {
                    // Either a block list on the next lines, or an empty value.
                    val items = ArrayList<String>()
                    while (i < lines.size && ITEM_LINE.matches(lines[i])) {
                        items += scalar(ITEM_LINE.find(lines[i])!!.groupValues[1])
                        i++
                    }
                    if (items.isNotEmpty()) properties += Property(key, Type.LIST, items.filter { it.isNotEmpty() })
                    else if (i >= lines.size || !lines[i].firstOrNull().let { it != null && it.isWhitespace() }) {
                        properties += Property(key, Type.TEXT, listOf(""))
                    }
                }
                raw.startsWith("[") && raw.endsWith("]") ->
                    properties += Property(key, Type.LIST, splitInline(raw.substring(1, raw.length - 1)))
                // Multi-line strings and inline maps stay in the text only.
                raw == "|" || raw == ">" || raw.startsWith("{") -> Unit
                else -> scalar(raw).let { properties += Property(key, typeOf(it, quoted = raw.first() == '"' || raw.first() == '\''), listOf(it)) }
            }
        }
        return Parsed(properties.distinctBy { it.key.lowercase() }, end.second, problem)
    }

    /** The note without its front matter. */
    fun body(text: String): String = text.substring(parse(text).bodyStart)

    /** Where the block ends: the index of its closing line, and the index where the body starts. */
    private fun blockEnd(text: String): Pair<Int, Int>? {
        if (!text.startsWith("---")) return null
        val firstBreak = text.indexOf('\n')
        if (firstBreak < 0 || text.substring(0, firstBreak).trimEnd() != "---") return null
        var lineStart = firstBreak + 1
        while (lineStart <= text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd).trimEnd()
            if (line == "---" || line == "...") return lineStart to minOf(lineEnd + 1, text.length)
            if (lineEnd == text.length) break
            lineStart = lineEnd + 1
        }
        return null
    }

    /** Writes a block for [properties], in order. Values that YAML could misread are quoted. */
    fun render(properties: List<Pair<String, List<String>>>): String = buildString {
        append("---\n")
        for ((key, values) in properties) {
            append(key).append(": ")
            if (values.size == 1) append(quote(values[0])) else append(values.joinToString(", ", "[", "]") { quote(it) })
            append('\n')
        }
        append("---\n")
    }

    private fun quote(value: String): String =
        if (value.isEmpty() || value.any { it in ":#[]{},\"'\n" } || value != value.trim() || value.startsWith("-")) {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
        } else {
            value
        }

    private fun scalar(raw: String): String {
        val value = raw.trim()
        return when {
            value.length >= 2 && value.first() == '"' && value.last() == '"' ->
                value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            value.length >= 2 && value.first() == '\'' && value.last() == '\'' ->
                value.substring(1, value.length - 1).replace("''", "'")
            // A comment after an unquoted value.
            else -> value.substringBefore(" #").trim()
        }
    }

    /** Splits `a, "b, c", d` at the commas that are not inside quotes. */
    private fun splitInline(inner: String): List<String> {
        val items = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in inner) {
            when {
                quote != null -> { current.append(ch); if (ch == quote) quote = null }
                ch == '"' || ch == '\'' -> { quote = ch; current.append(ch) }
                ch == ',' -> { items += scalar(current.toString()); current.clear() }
                else -> current.append(ch)
            }
        }
        items += scalar(current.toString())
        return items.filter { it.isNotEmpty() }
    }

    private fun typeOf(value: String, quoted: Boolean): Type = when {
        quoted -> Type.TEXT
        value == "true" || value == "false" -> Type.BOOL
        NUMBER.matches(value) -> Type.NUMBER
        dateOf(value) != null -> Type.DATE
        else -> Type.TEXT
    }

    private fun dateOf(value: String): LocalDate? =
        DATE.find(value)?.let { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }

    private fun numberOf(value: String): Double? = when {
        value == "true" -> 1.0
        value == "false" -> 0.0
        NUMBER.matches(value) -> value.toDoubleOrNull()
        else -> dateOf(value)?.toEpochDay()?.toDouble()
    }
}
