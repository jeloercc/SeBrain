package com.sebrain.app.chat

/**
 * What an answer shows under it: the notes it cited, and a couple of next questions to tap.
 * Decided in code from the route, at no model cost; the suggestions are in the language of the
 * user's message and are phrased so the app already knows what to do with them.
 */
object Suggestions {
    private val CITATION = Regex("\\[(\\d+)]")

    /** The notes [reply] cites, in order of first citation, as (id, title). */
    fun sources(reply: String, notes: List<ContextNote>): List<Pair<Long, String>> =
        CITATION.findAll(reply).mapNotNull { it.groupValues[1].toIntOrNull() }
            .mapNotNull { n -> notes.firstOrNull { it.number == n } }
            .distinctBy { it.id }
            .map { it.id to it.title.ifBlank { "Untitled" } }
            .toList()

    /** Up to two next questions; none after small talk or an action, or when the reply is empty. */
    fun next(route: Route, message: String, reply: String, cited: Boolean): List<String> {
        if (reply.isBlank() || route == Route.CHAT || route == Route.ACTION) return emptyList()
        val spanish = Assistant.language(message) == SPANISH
        return when {
            route == Route.PERSONAL && cited -> if (spanish) listOf("¿Qué más dicen mis notas sobre esto?", "Resúmelo en una frase") else listOf("What else do my notes say about this?", "Sum it up in one sentence")
            route == Route.PERSONAL -> emptyList()
            else -> if (spanish) listOf("Cuéntame más", "Haz una nota de esto") else listOf("Tell me more", "Make a note of this")
        }
    }

    private const val SPANISH = "Spanish"
}
