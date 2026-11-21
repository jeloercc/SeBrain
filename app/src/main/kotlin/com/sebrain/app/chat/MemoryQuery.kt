package com.sebrain.app.chat

import com.sebrain.core.vault.NoteStore.PropertyValue
import java.text.Normalizer

/**
 * Recall by front matter: the `key: value` pairs of the vault a message names ("status
 * pending", "proyecto Atlas"), so every note with that value can be given to the agent, not
 * only the most similar ones. Both the key and the value must be in the message, or "pending"
 * in passing would pull in every pending note.
 */
object MemoryQuery {
    const val MAX_VALUES = 2
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{M}+")

    /** The values [message] names, in the order it names them, at most [MAX_VALUES]. */
    fun mentioned(message: String, values: List<PropertyValue>): List<PropertyValue> {
        val text = " ${words(message)} "
        return values.mapNotNull { value ->
            val key = words(value.key)
            val wanted = words(value.value)
            if (key.isEmpty() || wanted.isEmpty()) return@mapNotNull null
            val keyAt = text.indexOf(" $key ")
            val valueAt = text.indexOf(" $wanted ")
            if (keyAt < 0 || valueAt < 0) null else minOf(keyAt, valueAt) to value
        }
            .distinctBy { (_, value) -> words(value.key) to words(value.value) }
            .sortedBy { it.first }
            .take(MAX_VALUES)
            .map { it.second }
    }

    /** "priority > 2", "due < 2026-11-20": a key of the vault, an operator from a fixed list, and a value. */
    data class Comparison(val key: String, val operator: String, val value: String, val isDate: Boolean) {
        val label: String get() = "$key $operator $value"
    }

    private val NUMBER_OPERATORS = listOf(
        ">=" to listOf("at least", "al menos", "como minimo", ">="),
        "<=" to listOf("at most", "como maximo", "<="),
        ">" to listOf("above", "over", "more than", "greater than", "higher than", "mayor que", "mayor a", "mas de", "mas que", "superior a", ">"),
        "<" to listOf("below", "under", "less than", "lower than", "menor que", "menor a", "menos de", "menos que", "inferior a", "<"),
    )
    private val DATE_OPERATORS = listOf(
        "<=" to listOf("by", "until", "hasta el", "hasta"),
        "<" to listOf("before", "antes del", "antes de"),
        ">" to listOf("after", "despues del", "despues de"),
    )
    private val WEEKDAYS = mapOf(
        "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5, "saturday" to 6, "sunday" to 7,
        "lunes" to 1, "martes" to 2, "miercoles" to 3, "jueves" to 4, "viernes" to 5, "sabado" to 6, "domingo" to 7,
    )
    private val NUMBER_AFTER_KEY = operatorRegex(NUMBER_OPERATORS, "(-?\\d+(?:[.,]\\d+)?)")
    private val DATE_AFTER_KEY = operatorRegex(
        DATE_OPERATORS,
        "(\\d{4}-\\d{2}-\\d{2}|today|hoy|tomorrow|manana|" + WEEKDAYS.keys.joinToString("|") + ")",
    )

    /**
     * The comparisons [message] makes on the vault's number and date [keys], in the order it
     * makes them, at most [MAX_VALUES]. A weekday is the next one on or after [today].
     */
    fun comparisons(message: String, keys: List<com.sebrain.core.vault.NoteStore.PropertyKey>, today: java.time.LocalDate): List<Comparison> {
        val text = " " + MARKS.replace(Normalizer.normalize(message.lowercase(), Normalizer.Form.NFD), "").replace(Regex("\\s+"), " ") + " "
        return keys.mapNotNull { key ->
            val name = words(key.key).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val at = text.indexOf(" $name ").takeIf { it >= 0 } ?: return@mapNotNull null
            val after = text.substring(at + name.length + 1)
            val comparison = if (key.type == "date") {
                DATE_AFTER_KEY.find(after)?.let { m -> dateOf(m.groupValues[2], today)?.let { Comparison(key.key, operatorOf(DATE_OPERATORS, m.groupValues[1]), it, true) } }
            } else {
                NUMBER_AFTER_KEY.find(after)?.let { m -> Comparison(key.key, operatorOf(NUMBER_OPERATORS, m.groupValues[1]), numberOf(m.groupValues[2]), false) }
            }
            comparison?.let { at to it }
        }.sortedBy { it.first }.take(MAX_VALUES).map { it.second }
    }

    /** The operator phrase right after the key ("priority above 2", "due is before Friday"), then its operand. */
    private fun operatorRegex(operators: List<Pair<String, List<String>>>, operand: String): Regex {
        val phrases = operators.flatMap { it.second }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        return Regex("^\\s*(?:is |es |de |of )?($phrases)\\s*$operand(?![\\p{L}\\p{N}])")
    }

    private fun operatorOf(operators: List<Pair<String, List<String>>>, phrase: String): String =
        operators.first { (_, phrases) -> phrase in phrases }.first

    private fun numberOf(text: String): String {
        val number = text.replace(',', '.').toDouble()
        return if (number == Math.floor(number) && !number.isInfinite()) number.toLong().toString() else number.toString()
    }

    private fun dateOf(text: String, today: java.time.LocalDate): String? = when (text) {
        "today", "hoy" -> today.toString()
        "tomorrow", "manana" -> today.plusDays(1).toString()
        in WEEKDAYS -> today.plusDays(((WEEKDAYS.getValue(text) - today.dayOfWeek.value + 7) % 7).toLong()).toString()
        else -> runCatching { java.time.LocalDate.parse(text).toString() }.getOrNull()
    }

    /** Lowercase words without accents, joined by single spaces. */
    private fun words(text: String): String =
        WORD.findAll(MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")).joinToString(" ") { it.value }
}
