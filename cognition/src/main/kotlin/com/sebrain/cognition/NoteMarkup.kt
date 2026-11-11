package com.sebrain.cognition

/** Reads Obsidian-style markup from a note: `#tags` and `[[links]]`. */
object NoteMarkup {
    data class Parsed(val tags: List<String>, val links: List<String>)

    // A tag starts with a letter or underscore, so "#123" and URL fragments are not tags.
    private val TAG = Regex("(?<![\\p{L}\\p{N}_#/&=?.:])#([\\p{L}_][\\p{L}\\p{N}_/-]*)")

    // [[Title]], [[Title|alias]] and [[Title#heading]] all link to "Title".
    private val LINK = Regex("\\[\\[([^\\[\\]|#\\n]+)(?:#[^\\[\\]|\\n]*)?(?:\\|[^\\[\\]\\n]*)?]]")
    private val CODE = Regex("```.*?```|`[^`\\n]*`", RegexOption.DOT_MATCHES_ALL)
    private const val MAX_TITLE = 80

    fun parse(text: String): Parsed {
        val prose = CODE.replace(text, " ")
        val tags = TAG.findAll(prose).map { it.groupValues[1].trimEnd('/', '-').lowercase() }
            .filter { it.isNotEmpty() }.distinct().toList()
        val links = LINK.findAll(prose).map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.toList()
        return Parsed(tags, links)
    }

    /** The subject if there is one; otherwise the first line, without Markdown heading marks. */
    fun titleOf(text: String, subject: String? = null): String {
        val candidate = subject?.trim()?.takeIf { it.isNotEmpty() }
            ?: text.lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull { it.isNotEmpty() }
            ?: "Untitled"
        return if (candidate.length <= MAX_TITLE) candidate else candidate.take(MAX_TITLE).trimEnd() + "…"
    }
}
