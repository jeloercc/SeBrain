package com.sebrain.app.chat

/**
 * Which part of the previous answer a note request is about. A small model given only "the
 * first one" rewrote the whole previous answer (Pixel, 2026-11-19), so the part is found here
 * and named in the prompt.
 */
object FollowUp {
    /**
     * What a note request follows up: one [point] of the previous answer, [everyPoint] of them,
     * or neither, meaning the previous answer as a whole. [points] are the answer's list items.
     */
    data class Target(val point: String?, val everyPoint: Boolean, val points: List<String>)

    private const val MAX_POINTS = 12
    private const val LAST = -1
    private val BULLET = Regex("^\\s*(?:[-*•+]|\\d{1,2}[.)])\\s+(.+)$")
    private val FENCE = Regex("^\\s*(```|~~~)")
    private val EMPHASIS = Regex("[*_`]+")

    private val ORDINALS = listOf(
        Regex("\\b(first|1st|primer[oa]?)\\b", RegexOption.IGNORE_CASE) to 0,
        Regex("\\b(second|2nd|segund[oa])\\b", RegexOption.IGNORE_CASE) to 1,
        Regex("\\b(third|3rd|tercer[oa]?)\\b", RegexOption.IGNORE_CASE) to 2,
        Regex("\\b(fourth|4th|cuart[oa])\\b", RegexOption.IGNORE_CASE) to 3,
        Regex("\\b(fifth|5th|quint[oa])\\b", RegexOption.IGNORE_CASE) to 4,
        Regex("(?<![\\p{L}])(last|[uú]ltim[oa])(?![\\p{L}])", RegexOption.IGNORE_CASE) to LAST,
    )
    private val NUMBERED = Regex("\\b(?:point|item|number|punto|n[uú]mero)\\s*#?(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
    private val POINT_WORD = Regex("\\b(points?|bullets?|items?|puntos?)\\b", RegexOption.IGNORE_CASE)
    private val EACH = Regex("\\b(each|every|all|cada|tod[oa]s)\\b", RegexOption.IGNORE_CASE)
    private val OWN_TOPIC = Regex("\\b(about|regarding|sobre|acerca de)\\s+\\S", RegexOption.IGNORE_CASE)

    /** The list items of [answer], outside code, without list marks or emphasis. */
    fun points(answer: String): List<String> {
        var inCode = false
        return answer.lines().mapNotNull { line ->
            if (FENCE.containsMatchIn(line)) inCode = !inCode
            if (inCode) return@mapNotNull null
            BULLET.find(line)?.groupValues?.get(1)?.let { EMPHASIS.replace(it, "").trim().trimEnd('.', ';', ':').trim() }
        }.filter { it.isNotEmpty() }.take(MAX_POINTS)
    }

    /** What [message], a note request, follows up in [previousAnswer]; null if it names a topic of its own. */
    fun of(message: String, previousAnswer: String?): Target? {
        if (previousAnswer.isNullOrBlank()) return null
        val points = points(previousAnswer)
        chosenIndex(message)?.let { chosen ->
            val index = if (chosen == LAST) points.lastIndex else chosen
            return Target(points.getOrNull(index), false, points)
        }
        val mentionsPoints = POINT_WORD.containsMatchIn(message)
        if (mentionsPoints && EACH.containsMatchIn(message) && points.isNotEmpty()) return Target(null, true, points)
        // Words that point back, or a note request with no topic of its own.
        if (Assistant.refersToPrevious(message) || mentionsPoints || !OWN_TOPIC.containsMatchIn(message)) return Target(null, false, points)
        return null
    }

    private fun chosenIndex(message: String): Int? {
        NUMBERED.find(message)?.let { return it.groupValues[1].toInt() - 1 }
        return ORDINALS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(message) }?.second
    }
}
