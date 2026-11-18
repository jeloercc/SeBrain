package com.sebrain.app

import com.sebrain.app.chat.NoteAliases
import com.sebrain.cognition.FrontMatter
import com.sebrain.cognition.NoteIndexer
import com.sebrain.core.vault.NoteStore
import com.sebrain.core.vault.VaultSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Saves the notes the assistant writes, when the user's message asks for one. The model itself
 * has no tools: it cannot create, change or delete notes on its own, so text inside a note can
 * never make it act on the vault. Every note saved is reported through [onAction].
 */
class NoteTools(
    private val session: VaultSession,
    private val indexer: NoteIndexer,
    private val onAction: (String) -> Unit,
) {
    /** Creates a note and records in its properties who wrote it: the local assistant, or an external model. */
    fun createNoteFrom(
        title: String,
        text: String,
        tags: List<String>,
        source: String,
        brain: String? = null,
        /** Other names the model proposed; only those that pass [NoteAliases.choose] are kept. */
        aliases: List<String> = emptyList(),
        /** Properties from the template the request named, already filled in (see [com.sebrain.app.chat.Templates]). */
        templateProperties: List<Pair<String, List<String>>> = emptyList(),
    ): String {
        // Links point to titles, so two notes with one title would make them ambiguous.
        if (session.withDatabase { NoteStore(it, brain).findByTitle(title.trim()) } != null) {
            return "A note titled \"${title.trim()}\" already exists; it was not saved again"
        }
        val created = session.withDatabase { db ->
            write(db) {
                val store = NoteStore(db, brain)
                // Chosen in the same transaction as the write, against the brain as it is now.
                val body = noteText(title, text, tags, source, NoteAliases.choose(title, aliases, aliasChecks(store), cleanTags(tags)), templateProperties)
                store.create(body, NoteIndexer.sha256Hex(body), Instant.now().toString(), ZoneId.systemDefault().id)
                    ?.also { id ->
                        // A note written in a brain is placed in its folder.
                        if (brain != null) store.setPath(id, store.freePath(brain, title))
                        indexer.index(db, id, body)
                    }
            }
        }
        return if (created != null) done("Created the note \"${title.trim()}\"") else "A note with the same content already exists"
    }

    /** The note in Obsidian format: where it came from and its other names, the title, the body, the tags. */
    private fun noteText(
        title: String,
        text: String,
        tags: List<String>,
        source: String,
        aliases: List<String>,
        templateProperties: List<Pair<String, List<String>>>,
    ): String = buildString {
        // Properties that say where the note came from, so it can be told apart and filtered.
        val properties = listOf("source" to listOf(source), "created" to listOf(LocalDate.now().toString())) + templateProperties +
            (if (aliases.isEmpty()) emptyList() else listOf("aliases" to aliases))
        append(FrontMatter.render(properties))
        append("# ").append(title.trim()).append("\n\n").append(text.trim())
        val clean = cleanTags(tags)
        if (clean.isNotEmpty()) append("\n\n").append(clean.joinToString(" ") { "#$it" })
    }

    /**
     * The checks [NoteAliases.choose] makes against the brain. Each one is a single indexed
     * query and runs only for the few names left after the checks done in memory.
     */
    private fun aliasChecks(store: NoteStore): NoteAliases.Brain {
        val notes = store.count()
        return NoteAliases.Brain(
            isTaken = { store.findByTitle(it) != null },
            tagsInUse = store.tags().mapTo(HashSet()) { it.first.lowercase() },
            // In more than half of the brain's notes, a name says nothing about one topic.
            isGeneric = { notes >= GENERIC_MIN_NOTES && store.notesMentioning(it) * 2 > notes },
        )
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

    private companion object {
        /** Below this, a brain is too small to tell a generic word from a shared topic. */
        const val GENERIC_MIN_NOTES = 4
    }
}
