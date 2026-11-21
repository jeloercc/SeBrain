package com.sebrain.app.eval

import java.text.Normalizer

/**
 * The pure parts of measuring search on the real vault with questions the phone's model writes
 * (`SyntheticRetrievalEval`): the prompt, cleaning its answer, and the numbers. Everything runs
 * on the phone; only the numbers are logged, never a question, a title or note text.
 */
object SearchProbe {
    const val QUESTION_TOKENS = 60
    private const val NOTE_CHARS = 2000
    private const val MIN_WORD = 4
    private const val MIN_QUESTION_WORDS = 3

    val SYSTEM = "You write search questions for a personal notes app. Given one note, write ONE question a " +
        "person would type months later to find it again, in the note's language. Ask about what the note " +
        "says, in your own words: do not copy its title or its sentences. Reply with the question only."

    fun prompt(title: String, body: String): String = "Note title: $title\n\nNote:\n${body.take(NOTE_CHARS)}"

    private val LABEL = Regex("^(question|pregunta)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val NUMBERING = Regex("^\\d+[.)]\\s*")
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{M}+")

    /** The question in a model reply: its first line, without labels, numbering or quotes; null if there is none. */
    fun question(reply: String): String? {
        val line = reply.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val clean = line.replace("**", "").replace("__", "").trim()
            .replace(NUMBERING, "").replace(LABEL, "")
            .trim('"', '“', '”', '\'', '`', ' ')
        if (WORD.findAll(clean).count() < MIN_QUESTION_WORDS) return null
        return if (clean.endsWith("?")) clean else "$clean?"
    }

    /** The share of the question's words (of [MIN_WORD] letters or more) that appear in [note], ignoring case and accents. */
    fun overlap(question: String, note: String): Double {
        val asked = words(question)
        if (asked.isEmpty()) return 0.0
        val written = words(note)
        return asked.count { it in written }.toDouble() / asked.size
    }

    /** Whether the question repeats a word of the title, which makes the search easier than a real one. */
    fun usesTitle(question: String, title: String): Boolean = words(title).any { it in words(question) }

    data class Summary(val questions: Int, val recallAt1: Double, val recallAt5: Double, val recallAt8: Double, val mrr: Double)

    /** Recall@1/5/8 and MRR for 1-based ranks, null where the note was not found. */
    fun summarize(ranks: List<Int?>): Summary {
        if (ranks.isEmpty()) return Summary(0, 0.0, 0.0, 0.0, 0.0)
        fun recall(k: Int) = ranks.count { it != null && it <= k }.toDouble() / ranks.size
        return Summary(ranks.size, recall(1), recall(5), recall(8), ranks.sumOf { if (it == null) 0.0 else 1.0 / it } / ranks.size)
    }

    private fun words(text: String): Set<String> =
        WORD.findAll(fold(text)).map { it.value }.filter { it.length >= MIN_WORD }.toSet()

    private fun fold(text: String): String = MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
}
