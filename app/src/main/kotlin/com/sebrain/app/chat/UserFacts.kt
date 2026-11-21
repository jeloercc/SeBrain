package com.sebrain.app.chat

/**
 * What the user tells the assistant about themselves, kept as memory (the Mem0 pattern): "me
 * llamo Julián", "my dentist is Dr. Ruiz", "remember that I park on level 3". Facts live as lines
 * of one pinned note, [TITLE], so they are in every prompt, in the backup and in an Obsidian
 * export, and the user reads and edits them like any note. A newer fact about the same thing
 * replaces the older one ("mi casillero es el 204" after "mi casillero es el 117").
 *
 * Only statements count, never questions: "¿cuál es mi casillero?" stores nothing.
 */
object UserFacts {
    const val TITLE = "About me"

    /** A fact: the line to keep, and the key two facts about the same thing share (null: never replaced). */
    data class Fact(val line: String, val key: String?)

    private class Pattern(val regex: Regex, val fact: (MatchResult) -> Fact)

    private fun clean(text: String) = text.trim().trimEnd('.', '!', ' ').replace(Regex("\\s+"), " ")

    private val PATTERNS = listOf(
        // Explicit requests keep the user's words.
        Pattern(Regex("(?i)^(?:por favor,? )?(?:recuerda|acuérdate de|acuerdate de) que (.+)$")) { Fact(clean(it.groupValues[1]), null) },
        Pattern(Regex("(?i)^(?:please )?remember that (.+)$")) { Fact(clean(it.groupValues[1]), null) },
        Pattern(Regex("(?i)^(?:hola,? )?me llamo (\\p{L}+(?: \\p{L}+)?)$")) { Fact("Me llamo ${clean(it.groupValues[1])}", "name") },
        Pattern(Regex("(?i)^(?:hi,? )?my name is (\\p{L}+(?: \\p{L}+)?)$")) { Fact("My name is ${clean(it.groupValues[1])}", "name") },
        Pattern(Regex("(?i)^soy al[eé]rgic[oa] a (.+)$")) { Fact("Soy alérgico a ${clean(it.groupValues[1])}", "allergy ${clean(it.groupValues[1]).lowercase()}") },
        Pattern(Regex("(?i)^i'?m allergic to (.+)$|^i am allergic to (.+)$")) { m ->
            val what = clean(m.groupValues[1].ifEmpty { m.groupValues[2] })
            Fact("I'm allergic to $what", "allergy ${what.lowercase()}")
        },
        Pattern(Regex("(?i)^vivo en (.+)$")) { Fact("Vivo en ${clean(it.groupValues[1])}", "home") },
        Pattern(Regex("(?i)^i live in (.+)$")) { Fact("I live in ${clean(it.groupValues[1])}", "home") },
        Pattern(Regex("(?i)^trabajo (en|para|como) (.+)$")) { Fact("Trabajo ${it.groupValues[1].lowercase()} ${clean(it.groupValues[2])}", "work") },
        Pattern(Regex("(?i)^i work (at|for|as|in) (.+)$")) { Fact("I work ${it.groupValues[1].lowercase()} ${clean(it.groupValues[2])}", "work") },
        // "mi dentista es la Dra. Ruiz", "my locker is 204": the thing named is the key.
        Pattern(Regex("(?i)^(mi|mis) ([\\p{L} ]{2,30}?) (?:es|son|está|esta) (.+)$")) {
            Fact("${it.groupValues[1].lowercase()} ${clean(it.groupValues[2])} es ${clean(it.groupValues[3])}".replaceFirstChar(Char::uppercase), "my ${clean(it.groupValues[2]).lowercase()}")
        },
        Pattern(Regex("(?i)^my ([\\p{L} ]{2,30}?) (?:is|are) (.+)$")) {
            Fact("My ${clean(it.groupValues[1])} is ${clean(it.groupValues[2])}", "my ${clean(it.groupValues[1]).lowercase()}")
        },
        Pattern(Regex("(?i)^(?:yo )?prefiero (.+)$")) { Fact("Prefiero ${clean(it.groupValues[1])}", null) },
        Pattern(Regex("(?i)^i prefer (.+)$")) { Fact("I prefer ${clean(it.groupValues[1])}", null) },
    )

    /** The fact [message] states about the user, or null for a question or anything else. */
    fun detect(message: String): Fact? {
        val text = message.trim()
        if (text.isEmpty() || text.contains('?') || text.contains('¿') || text.length > MAX_CHARS) return null
        return PATTERNS.firstNotNullOfOrNull { pattern -> pattern.regex.matchEntire(text)?.let(pattern.fact) }
            ?.takeIf { it.line.length >= MIN_CHARS }
    }

    /**
     * [noteText] (the [TITLE] note, or null if there is none yet) with [fact] in it: a line with
     * the same key is replaced, an identical line is not added twice, anything else is added at
     * the end of the list. The note stays pinned.
     */
    fun merge(noteText: String?, fact: Fact): String {
        val lines = (noteText ?: "# $TITLE\n\nWhat I have told SeBrain about myself.\n").lines().toMutableList()
        val entry = "- ${fact.line}${fact.key?.let { " <!-- $it -->" } ?: ""}"
        val same = lines.indexOfFirst { line ->
            line.startsWith("- ") && ((fact.key != null && line.endsWith("<!-- ${fact.key} -->")) || line.removePrefix("- ").substringBefore(" <!--").equals(fact.line, ignoreCase = true))
        }
        if (same >= 0) {
            lines[same] = entry
        } else {
            val lastItem = lines.indexOfLast { it.startsWith("- ") }
            if (lastItem >= 0) lines.add(lastItem + 1, entry) else lines.add(lines.indexOfLast { it.isNotBlank() } + 1, "\n$entry")
        }
        return Pins.pin(lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n"))
    }

    private const val MAX_CHARS = 200
    private const val MIN_CHARS = 6
}
