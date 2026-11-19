package com.sebrain.app.chat

import java.text.Normalizer

/**
 * Cleans text that came from outside (a note shared from another app) before the model reads
 * it: a line whose label addresses the assistant ("AI assistant: …", "Instrucción para el
 * asistente: …", "Step 1 for the AI: …", `<!-- ASSISTANT: … -->`) is replaced by a notice, and
 * so is a front-matter property named for it (`note_for_ai`). Measured on the Pixel on
 * 2026-11-18: an instruction hidden in a list item was followed despite the system rule.
 * Only shared notes are cleaned; the user's own notes are left as written.
 */
object OutsideText {
    const val REMOVED = "[a line addressed to the assistant was removed]"

    /** Words that make a label address the assistant. "ia" and "ai" stand alone, not inside other words. */
    private val ADDRESSEE = Regex("\\b(ai|ia|assistant|asistente|llm|chatbot|bot|gpt|chatgpt|claude|gemini|copilot)\\b")

    /** "System"/"sistema" only counts where a prompt is usually faked: in a quote or a comment. */
    private val FAKED_SYSTEM = Regex("^\\s*(>|<!--)\\s*(system|sistema|developer|instructions?|instrucciones?)\\s*:")

    private const val MAX_LABEL = 40

    /** The text before the first colon of a line, after list, quote and comment markers, if short enough to be a label. */
    private val LABEL = Regex("^\\s*(?:[-*+>]\\s*|\\d+[.)]\\s*|<!--\\s*)*([^:\\n]{1,$MAX_LABEL})\\s*:")

    /** A list marker is kept, so a list still reads as a list. */
    private val LIST_MARKER = Regex("^\\s*[-*+]\\s+")

    private val PROPERTY_KEY = Regex("(?:^|[_\\-\\s])(ai|ia|assistant|asistente|llm|bot|gpt|prompt)(?:$|[_\\-\\s])")

    fun neutralize(text: String): String = text.lines().joinToString("\n") { line ->
        val folded = fold(line)
        val label = LABEL.find(folded)?.groupValues?.get(1)
        val addressed = (label != null && ADDRESSEE.containsMatchIn(label)) || FAKED_SYSTEM.containsMatchIn(folded)
        if (addressed) LIST_MARKER.find(line)?.value.orEmpty() + REMOVED else line
    }

    /** [properties] ("key: value") without those whose key is named for the assistant. */
    fun properties(properties: List<String>): List<String> =
        properties.filterNot { property -> PROPERTY_KEY.containsMatchIn(fold(property.substringBefore(':'))) }

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
