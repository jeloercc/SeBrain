package com.sebrain.cognition

/** Converts notes to and from the small Markdown interchange format shared with Obsidian. */
object MarkdownInterop {
    data class Imported(val subject: String, val text: String, val markup: NoteMarkup.Parsed)

    /** Returns a safe Markdown filename while preserving the note's title as much as possible. */
    fun fileName(title: String): String {
        val safe = title.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""[\u0000-\u001f]"""), "_")
            .trim('.', ' ', '_')
            .ifBlank { "Untitled" }
        return if (safe.endsWith(".md", ignoreCase = true)) safe else "$safe.md"
    }

    /** Uses the document filename as the subject, while extracting tags and links from its text. */
    fun importDocument(fileName: String, text: String): Imported {
        val subject = fileName.substringAfterLast('/').removeSuffixIgnoreCase(".md").trim()
            .ifBlank { "Untitled" }
        return Imported(subject, text, NoteMarkup.parse(text))
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}
