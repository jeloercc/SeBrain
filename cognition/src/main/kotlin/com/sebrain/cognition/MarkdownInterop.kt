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

    /**
     * The path a vault file gets as a note: its place under the vault folder, without ".md".
     * Null for a file that is not a Markdown note or that lives in a hidden folder such as
     * `.obsidian` or `.trash`.
     */
    fun notePath(relativeFile: String): String? {
        val parts = relativeFile.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it.startsWith(".") }) return null
        val file = parts.last()
        if (!file.endsWith(".md", ignoreCase = true) || file.length == 3) return null
        return (parts.dropLast(1) + file.dropLast(3).trim()).joinToString("/")
    }

    /** The file a note is exported as, under the folder being written: its path, or its title at the root. */
    fun exportFile(path: String?, title: String): List<String> {
        val parts = path?.split('/')?.filter { it.isNotBlank() }.orEmpty()
        return if (parts.isEmpty()) listOf(fileName(title)) else parts.dropLast(1).map { fileName(it).removeSuffix(".md") } + fileName(parts.last())
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}
