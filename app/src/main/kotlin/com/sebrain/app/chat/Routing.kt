package com.sebrain.app.chat

import kotlin.math.sqrt

/**
 * What a chat message is for, decided before the model runs (the adaptive-RAG pattern): the
 * route chooses the prompt and how strictly notes are let in, so a general question is never
 * buried under unrelated notes and answered with "your notes don't say" (2026-11-20).
 *
 * Rules decide what is certain; the rest goes by meaning ([SemanticRouter]), and when neither is
 * sure the message is [Route.GENERAL], which still lets in a note that clearly relates.
 */
enum class Route {
    /** Greetings, thanks, small talk: no search, a short natural reply. */
    CHAT,

    /** World knowledge, how-to, ideas: answered from the model; notes only if clearly related. */
    GENERAL,

    /** About the user's own life or notes: answered from the notes, never guessed. */
    PERSONAL,

    /** Asks to write, edit or file something in the vault. */
    ACTION,
}

/**
 * How close a note must be to the message, in vector distance (L2 over unit vectors, 0 to about
 * 1.41), to be given to the model. Calibrated on TrustEvalTest (see docs/benchmarks.md): the note
 * that answers a personal question must pass; for a general question, nothing should.
 */
object RelevanceGate {
    /** A personal question or an action: the notes are the point, so the gate is wide. */
    const val PERSONAL = 1.10

    /** A general question: only a note that is clearly about the same thing. */
    const val GENERAL = 0.85

    /** The farthest a search hit may be for [route]; small talk takes none. */
    fun maxDistance(route: Route): Double = when (route) {
        Route.CHAT -> NONE
        Route.GENERAL -> GENERAL
        Route.PERSONAL, Route.ACTION -> PERSONAL
    }

    private const val NONE = -1.0
}

object Routing {
    /** "make a note", "edit my note", "add to today's note": the vault actions the app already handles. */
    fun isAction(message: String): Boolean =
        Assistant.wantsNote(message) || NoteEdits.kindOf(message) != null || NoteEdits.wantsVaultReview(message) ||
            DailyNotes.request(message) != null

    /**
     * The route the rules are sure of, or null. Possessives and verbs about what the user did make
     * a message personal; "me" alone does not ("tell me about NASA" is a general question).
     */
    fun byRules(message: String): Route? {
        val text = message.trim()
        return when {
            isAction(text) -> Route.ACTION
            SMALL_TALK.matches(text) -> Route.CHAT
            PERSONAL.containsMatchIn(text) -> Route.PERSONAL
            else -> null
        }
    }

    /** The route: rules first, then meaning; [Route.GENERAL] when neither is sure. */
    fun route(message: String, vector: FloatArray?, router: SemanticRouter?): Route =
        byRules(message) ?: vector?.let { router?.route(it) } ?: Route.GENERAL

    private const val GREETING =
        "hola|hi|hey|hello|buenas|buenos d[ií]as|buenas (tardes|noches)|good (morning|afternoon|evening)|" +
            "gracias|muchas gracias|thanks|thank you|ok|vale|perfecto|genial|great|cool|" +
            "(c[oó]mo|como) (est[aá]s|va todo|te va)|how are you( doing)?|qu[eé] tal|what'?s up|" +
            "adi[oó]s|bye|hasta luego|see you"

    /** A message made only of greetings, thanks or "how are you", in any order. */
    private val SMALL_TALK = Regex("(?i)^[\\s¡!¿?.,]*(($GREETING)[\\s¡!¿?.,]*){1,3}$")

    private val PERSONAL = Regex(
        "(?i)\\b(mi|mis|nuestro|nuestra|nuestros|nuestras|my|mine|our)\\b|" +
            "\\b(did|do|have|had|was|am) i\\b|\\bi (have|had|did|was|wrote|saved|said|told|need|put|left)\\b|" +
            "\\b(tengo|ten[ií]a|hice|guard[eé]|escrib[ií]|dije|apunt[eé]|anot[eé]|dej[eé]|me toca)\\b|" +
            "\\b(ayer|yesterday|la semana pasada|last week|el mes pasado|last month)\\b|" +
            "\\b(notas?|notes?|apuntes|vault|b[oó]veda)\\b",
    )
}

/**
 * Routes by meaning: the message vector is compared with example phrasings of each route, and the
 * route of the closest examples wins if it is clearly ahead (the semantic-router pattern). The
 * vector is the one search already computes, so routing costs no extra model call.
 */
class SemanticRouter(private val examples: Map<Route, List<FloatArray>>) {
    /** The closest route, or null when the best two are too close to call. */
    fun route(vector: FloatArray): Route? {
        val ranked = examples.mapValues { (_, vectors) -> topMean(vectors.map { cosine(it, vector) }) }
            .entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: Double.NEGATIVE_INFINITY
        return if (best.value >= MIN_SIMILARITY && best.value - second >= MIN_MARGIN) best.key else null
    }

    /** The mean of the [TOP] best similarities: one odd example cannot decide alone. */
    private fun topMean(similarities: List<Double>): Double =
        similarities.sortedDescending().take(TOP).takeIf { it.isNotEmpty() }?.average() ?: Double.NEGATIVE_INFINITY

    companion object {
        private const val TOP = 3
        private const val MIN_SIMILARITY = 0.45
        private const val MIN_MARGIN = 0.03

        /** Builds a router by embedding [EXAMPLES] once with [embed]. */
        fun build(embed: (String) -> FloatArray): SemanticRouter =
            SemanticRouter(EXAMPLES.mapValues { (_, phrases) -> phrases.map(embed) })

        fun cosine(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
        }

        /** Example phrasings in English and Spanish. ACTION is decided by rules only. */
        val EXAMPLES: Map<Route, List<String>> = mapOf(
            Route.CHAT to listOf(
                "hola, ¿qué tal tu día?", "hi there, how's it going?", "gracias, me ayudaste mucho", "thanks, that helped a lot",
                "jaja qué bueno", "haha that's funny", "buenas noches", "good morning!", "¿quién eres?", "who are you?",
                "estoy cansado hoy", "I'm bored", "cuéntame algo divertido", "tell me a joke",
            ),
            Route.GENERAL to listOf(
                "¿quién fue el primer fotógrafo?", "who invented the telephone?", "¿qué es la NASA?", "what can you tell me about black holes?",
                "explícame cómo funciona el DNS", "how does a VPN work?", "¿cuál es la capital de Australia?", "what is photosynthesis?",
                "dame ideas para una cena rápida", "write a bash script that lists files by size", "¿qué diferencia hay entre TCP y UDP?",
                "summarize the history of the Roman empire", "¿cómo se calcula el interés compuesto?", "recommend a good sci-fi book",
            ),
            Route.PERSONAL to listOf(
                "¿cuándo es la cita con el dentista?", "when is the car service due?", "¿cuál es la clave del candado del gimnasio?",
                "what's the booking code for the hotel?", "¿qué dijo Laura sobre la reunión?", "what did we decide about the backup?",
                "¿a qué hora sale el vuelo a Lisboa?", "who is the contact for project Heron?", "¿cuándo cumple años Tomás?",
                "what's the IP of the NAS?", "¿qué tareas tengo pendientes?", "what was I reading?", "¿cómo se llama el veterinario?",
            ),
        )
    }
}
