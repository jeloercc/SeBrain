package com.sebrain.cognition

/**
 * Reads Obsidian-style markup from a note: `#tags`, `[[links]]` and the properties of its
 * front matter.
 */
object NoteMarkup {
    /**
     * A link to another note. [anchor] is the part after `#`: a heading, or `^id` for a block;
     * empty for a link to the whole note. [embed] is true for `![[Note]]`, which shows the
     * target inside this note.
     */
    data class Link(val target: String, val anchor: String = "", val embed: Boolean = false)

    data class Parsed(
        val tags: List<String>,
        /** The distinct notes linked to, by name. */
        val links: List<String>,
        val linkDetails: List<Link> = emptyList(),
        val aliases: List<String> = emptyList(),
        val properties: List<FrontMatter.Property> = emptyList(),
        /** Why the front matter could not be read in full, if it could not. */
        val problem: String? = null,
    )

    // A tag starts with a letter or underscore, so "#123" and URL fragments are not tags.
    private val TAG = Regex("(?<![\\p{L}\\p{N}_#/&=?.:])#([\\p{L}_][\\p{L}\\p{N}_/-]*)")

    // [[Title]], [[Title|shown]], [[Title#heading]], [[Title#^block]] and the embed ![[Title]].
    private val LINK = Regex("(!?)\\[\\[([^\\[\\]|#\\n]*)(?:#([^\\[\\]|\\n]*))?(?:\\|[^\\[\\]\\n]*)?]]")
    private val CODE = Regex("```.*?```|`[^`\\n]*`", RegexOption.DOT_MATCHES_ALL)
    private const val MAX_TITLE = 80

    fun parse(text: String): Parsed {
        val front = FrontMatter.parse(text)
        val prose = CODE.replace(text.substring(front.bodyStart), " ")
        // Tags written in the text, then the ones listed in the properties.
        val written = TAG.findAll(prose).map { it.groupValues[1] } +
            front.valuesOf("tags", "tag").asSequence().map { it.trim().trimStart('#') }
        val tags = written.map { it.trimEnd('/', '-').lowercase() }.filter { it.isNotEmpty() }
            // A nested tag also counts as each of its parents: #redes/tcp is found under #redes.
            .flatMap { tag -> tag.split('/').filter { it.isNotEmpty() }.runningReduce { path, part -> "$path/$part" }.asReversed() }
            .distinct().toList()
        val details = LINK.findAll(prose)
            .map { Link(it.groupValues[2].trim(), it.groupValues[3].trim(), it.groupValues[1] == "!") }
            // [[#Heading]] points inside the same note; it is not a link to another one.
            .filter { it.target.isNotEmpty() }
            .distinctBy { Triple(it.target.lowercase(), it.anchor.lowercase(), it.embed) }.toList()
        return Parsed(
            tags = tags,
            links = details.map { it.target }.distinctBy { it.lowercase() },
            linkDetails = details,
            aliases = front.valuesOf("aliases", "alias").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
            properties = front.properties,
            problem = front.problem,
        )
    }

    /**
     * The subject if there is one; otherwise the first line of the note's own text, without
     * Markdown heading marks. The front matter is skipped, or the title would be "---".
     */
    fun titleOf(text: String, subject: String? = null): String {
        val candidate = subject?.trim()?.takeIf { it.isNotEmpty() }
            ?: FrontMatter.body(text).lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull { it.isNotEmpty() }
            ?: "Untitled"
        return if (candidate.length <= MAX_TITLE) candidate else candidate.take(MAX_TITLE).trimEnd() + "…"
    }
}
