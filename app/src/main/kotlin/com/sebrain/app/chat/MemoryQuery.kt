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

    /** Lowercase words without accents, joined by single spaces. */
    private fun words(text: String): String =
        WORD.findAll(MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")).joinToString(" ") { it.value }
}
