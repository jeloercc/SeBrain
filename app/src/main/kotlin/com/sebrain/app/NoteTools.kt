package com.sebrain.app

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.sebrain.cognition.NoteIndexer
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.HybridSearch
import com.sebrain.core.vault.NoteStore
import com.sebrain.core.vault.VaultSession
import java.time.Instant
import java.time.ZoneId
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * What the assistant may do: act on notes, and nothing else. It cannot delete notes, launch
 * other apps or run code. Every action is reported through [onAction] so the user sees it.
 */
class NoteTools(
    private val session: VaultSession,
    private val indexer: NoteIndexer,
    private val onAction: (String) -> Unit,
) : ToolSet {
    @Tool(description = "Create a new note in the user's vault")
    fun createNote(
        @ToolParam(description = "Short title of the note") title: String,
        @ToolParam(description = "Body of the note, in Markdown") text: String,
        @ToolParam(description = "Tags without the # sign. Use an empty list for none") tags: List<String>,
    ): String {
        val body = buildString {
            append("# ").append(title.trim()).append("\n\n").append(text.trim())
            val clean = cleanTags(tags)
            if (clean.isNotEmpty()) append("\n\n").append(clean.joinToString(" ") { "#$it" })
        }
        val created = session.withDatabase { db ->
            write(db) {
                NoteStore(db).create(body, NoteIndexer.sha256Hex(body), Instant.now().toString(), ZoneId.systemDefault().id)
                    ?.also { indexer.index(db, it, body) }
            }
        }
        return if (created != null) done("Created the note \"${title.trim()}\"") else "A note with the same content already exists"
    }

    @Tool(description = "Search the user's notes by words. Returns titles and short excerpts")
    fun searchNotes(@ToolParam(description = "Words to search for") query: String): String = session.withDatabase { db ->
        val captures = CaptureStore(db)
        val hits = HybridSearch(db).search(query, null, 5).mapNotNull { hit ->
            captures.summaryOf(hit.captureId)?.let { "${it.title.orEmpty()}: ${hit.text.replace('\n', ' ').take(200)}" }
        }.distinct()
        if (hits.isEmpty()) "No note matches" else hits.joinToString("\n")
    }

    @Tool(description = "Add tags to an existing note, found by its title")
    fun addTags(
        @ToolParam(description = "Title of the note") title: String,
        @ToolParam(description = "Tags to add, without the # sign") tags: List<String>,
    ): String {
        val clean = cleanTags(tags)
        if (clean.isEmpty()) return "No valid tag was given"
        return append(title, clean.joinToString(" ") { "#$it" }) { "Tagged \"$it\" with ${clean.joinToString(" ") { t -> "#$t" }}" }
    }

    @Tool(description = "Link one existing note to another, both found by title")
    fun linkNotes(
        @ToolParam(description = "Title of the note that will contain the link") fromTitle: String,
        @ToolParam(description = "Title of the note to link to") toTitle: String,
    ): String {
        val target = session.withDatabase { NoteStore(it).findByTitle(toTitle.trim()) } ?: return "There is no note titled \"$toTitle\""
        val targetTitle = session.withDatabase { NoteStore(it).get(target)?.title } ?: toTitle.trim()
        return append(fromTitle, "[[$targetTitle]]") { "Linked \"$it\" to \"$targetTitle\"" }
    }

    /** Appends a line to the note titled [title] and reindexes it. */
    private fun append(title: String, line: String, message: (String) -> String): String = session.withDatabase { db ->
        val store = NoteStore(db)
        val note = store.findByTitle(title.trim())?.let(store::get) ?: return@withDatabase "There is no note titled \"$title\""
        val text = note.text.trimEnd() + "\n\n" + line
        val updated = write(db) {
            store.updateText(note.id, text, NoteIndexer.sha256Hex(text), Instant.now().toString())
                .also { if (it) indexer.index(db, note.id, text) }
        }
        if (updated) done(message(note.title)) else "The note could not be changed"
    }

    private fun <T> write(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun cleanTags(tags: List<String>): List<String> =
        tags.map { it.trim().trimStart('#').lowercase().replace(Regex("[^\\p{L}\\p{N}_/-]"), "-").trim('-') }
            .filter { it.isNotEmpty() && !it.first().isDigit() }.distinct()

    private fun done(message: String): String {
        onAction(message)
        return message
    }
}
