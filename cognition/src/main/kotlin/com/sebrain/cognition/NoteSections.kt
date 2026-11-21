package com.sebrain.cognition

/**
 * The parts of a note that a link can point to, and the text edits that turn a mention into a
 * link. Headings and block ids are not stored anywhere: they are found by reading the note,
 * so the note's text stays the only source of truth.
 */
object NoteSections {
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val BLOCK_ID = Regex("\\s\\^([\\p{L}\\p{N}-]+)\\s*$")
    private val FENCE = Regex("^\\s*(```|~~~)")
    /**
     * Text a mention is never linked in: links, code, heading lines (a link would change the
     * note's title or a section name) and `#tags` (as `NoteMarkup` reads them).
     */
    private val NOT_LINKABLE = Regex(
        "!?\\[\\[[^\\]]*]]|```.*?```|`[^`\\n]*`|(?m:^#{1,6}[ \\t][^\\n]*)|(?<![\\p{L}\\p{N}_#/&=?.:])#[\\p{L}_][\\p{L}\\p{N}_/-]*",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** What `[[target#anchor|display]]` holds between its brackets. */
    data class Reference(val target: String, val anchor: String, val display: String?) {
        /** What to show for the link when the note gives no display text. */
        val label: String get() = display ?: if (anchor.isEmpty()) target else "$target › ${anchor.removePrefix("^")}"
    }

    /** The key under which the text of an embedded note is handed to the screen that shows it. */
    fun embedKey(target: String, anchor: String): String = target.trim().lowercase() + "#" + anchor.trim().lowercase()

    fun reference(inner: String): Reference {
        val display = inner.substringAfter('|', "").trim().takeIf { '|' in inner && it.isNotEmpty() }
        val address = inner.substringBefore('|')
        return Reference(address.substringBefore('#').trim(), address.substringAfter('#', "").trim(), display)
    }

    /**
     * The part of [text] that [anchor] names: the whole note without its front matter for an
     * empty anchor, a heading's section up to the next heading of the same or a higher level,
     * or the block that ends with `^id`. Null if the note has no such heading or block.
     */
    fun extract(text: String, anchor: String): String? {
        val body = FrontMatter.body(text)
        if (anchor.isEmpty()) return body.trim()
        val lines = body.lines()
        return if (anchor.startsWith("^")) block(lines, anchor.drop(1)) else section(lines, anchor)
    }

    private fun section(lines: List<String>, heading: String): String? {
        var fenced = false
        var start = -1
        var level = 0
        for ((i, line) in lines.withIndex()) {
            if (FENCE.containsMatchIn(line)) fenced = !fenced
            if (fenced) continue
            val match = HEADING.find(line) ?: continue
            if (start < 0) {
                if (match.groupValues[2].trim().equals(heading.trim(), ignoreCase = true)) {
                    start = i
                    level = match.groupValues[1].length
                }
            } else if (match.groupValues[1].length <= level) {
                return lines.subList(start, i).joinToString("\n").trim()
            }
        }
        return if (start < 0) null else lines.subList(start, lines.size).joinToString("\n").trim()
    }

    private fun block(lines: List<String>, id: String): String? {
        val at = lines.indexOfFirst { BLOCK_ID.find(it)?.groupValues?.get(1).equals(id, ignoreCase = true) }
        if (at < 0) return null
        // A block is the paragraph that ends at the marked line.
        var start = at
        while (start > 0 && lines[start - 1].isNotBlank()) start--
        return lines.subList(start, at + 1).joinToString("\n").replace(BLOCK_ID, "").trim()
    }

    /**
     * Turns the first plain mention of one of [names] in [text] into a link to [title]. A
     * mention already inside a link or inside code is left alone. The words stay as the user
     * wrote them: "el protocolo tcp" becomes `[[Protocolo TCP|protocolo tcp]]`. Returns null if
     * there is nothing to link.
     */
    fun linkMention(text: String, title: String, names: List<String>): String? {
        val start = FrontMatter.parse(text).bodyStart
        // The note's own names ("Secure Shell" in the SSH note) are about this note, so no word
        // inside them links to another one.
        val ownNames = (NoteMarkup.parse(text).aliases + listOfNotNull(ownTitle(text, start))).filter { it.trim().length >= MIN_OWN_NAME }
        val taken = NOT_LINKABLE.findAll(text, start).map { it.range }.toList() +
            ownNames.flatMap { own -> mentions(text, own, start).map { it.range }.toList() }
        for (name in names.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            val match = mentions(text, name, start).firstOrNull { m -> taken.none { m.range.first >= it.first && m.range.last <= it.last } }
                ?: continue
            val link = if (match.value == title) "[[$title]]" else "[[$title|${match.value}]]"
            return text.replaceRange(match.range, link)
        }
        return null
    }

    /** The note's `# Title`, if it has one; a first line of prose is not a name. */
    private fun ownTitle(text: String, start: Int): String? =
        text.substring(start).lineSequence().mapNotNull { HEADING.matchEntire(it.trim()) }
            .firstOrNull { it.groupValues[1].length == 1 }?.groupValues?.get(2)?.takeIf { it.isNotBlank() }

    /** Where [name] appears in [text] from [start], as whole words, ignoring case. */
    private fun mentions(text: String, name: String, start: Int): Sequence<MatchResult> =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(name.trim()) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).findAll(text, start)

    /** Shorter own names ("os") would block words that are not about the note. */
    private const val MIN_OWN_NAME = 3
}
