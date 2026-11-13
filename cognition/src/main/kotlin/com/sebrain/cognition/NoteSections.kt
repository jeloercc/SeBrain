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
    private val LINK_OR_CODE = Regex("!?\\[\\[[^\\]]*]]|```.*?```|`[^`\\n]*`", RegexOption.DOT_MATCHES_ALL)

    /** What `[[target#anchor|display]]` holds between its brackets. */
    data class Reference(val target: String, val anchor: String, val display: String?) {
        /** What to show for the link when the note gives no display text. */
        val label: String get() = display ?: if (anchor.isEmpty()) target else "$target › ${anchor.removePrefix("^")}"
    }

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
        val taken = LINK_OR_CODE.findAll(text, start).map { it.range }.toList()
        for (name in names.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(name.trim()) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            val match = pattern.findAll(text, start).firstOrNull { m -> taken.none { m.range.first >= it.first && m.range.last <= it.last } }
                ?: continue
            val link = if (match.value == title) "[[$title]]" else "[[$title|${match.value}]]"
            return text.replaceRange(match.range, link)
        }
        return null
    }
}
