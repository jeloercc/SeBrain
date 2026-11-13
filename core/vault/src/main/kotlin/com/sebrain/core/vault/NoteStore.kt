package com.sebrain.core.vault

import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase

data class Note(
    val id: Long,
    val title: String,
    val text: String,
    val capturedAt: String,
    val source: String,
    val tags: List<String>,
    /** The note's place in the folder tree, such as `Redes/TCP`; null for a note at the root without one. */
    val path: String? = null,
    val aliases: List<String> = emptyList(),
    val properties: List<NoteProperty> = emptyList(),
)

/** A link from one note to another, as written: `[[target#anchor]]`, or `![[target]]` when [embed]. */
data class NoteLink(val target: String, val anchor: String = "", val embed: Boolean = false)

/**
 * A property from a note's front matter. [type] is text, number, date, bool or list. [numbers]
 * runs parallel to [values] and holds what can be compared: a number, a date as its epoch day,
 * a boolean as 0 or 1.
 */
data class NoteProperty(
    val key: String,
    val type: String,
    val values: List<String>,
    val numbers: List<Double?> = values.map { null },
)

/** Notes, tags and links. Every capture is a note. Use it inside [VaultSession.withDatabase]. */
/** Notes and the links between them. A node without an id is a link target that does not exist yet. */
class NoteGraph(val nodes: List<Node>, val edges: List<Pair<Int, Int>>) {
    class Node(val id: Long?, val title: String, val source: String = "unknown")
}

class NoteStore(private val db: SQLiteDatabase) {
    /** The newest [limit] notes and every link between them, as indexes into the node list. */
    fun graph(limit: Int = 2000, sources: Set<String>? = null): NoteGraph {
        val nodes = ArrayList<NoteGraph.Node>()
        val byId = HashMap<Long, Int>()
        val byTitle = HashMap<String, Int>()
        val sourceClause = when {
            sources == null -> ""
            sources.isEmpty() -> " WHERE 0"
            else -> " WHERE source IN (${sources.joinToString { "?" }})"
        }
        val args = sources?.toTypedArray() ?: emptyArray()
        db.rawQuery(
            "SELECT id, COALESCE(title, substr(text, 1, 40)), source FROM capture$sourceClause ORDER BY id DESC LIMIT $limit",
            args,
        ).use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1).orEmpty()
                byId[c.getLong(0)] = nodes.size
                byTitle.putIfAbsent(title.lowercase(), nodes.size)
                nodes += NoteGraph.Node(c.getLong(0), title, c.getString(2))
            }
        }
        // A link may name a note by one of its aliases.
        db.rawQuery("SELECT capture_id, name FROM alias").use { c ->
            while (c.moveToNext()) byId[c.getLong(0)]?.let { byTitle.putIfAbsent(c.getString(1).lowercase(), it) }
        }
        val edges = LinkedHashSet<Pair<Int, Int>>()
        db.rawQuery("SELECT DISTINCT source_id, target_title FROM link").use { c ->
            while (c.moveToNext()) {
                val source = byId[c.getLong(0)] ?: continue
                val target = byTitle.getOrPut(c.getString(1).lowercase()) {
                    nodes += NoteGraph.Node(null, c.getString(1), "unresolved")
                    nodes.size - 1
                }
                if (source != target) edges += source to target
            }
        }
        return NoteGraph(nodes, edges.toList())
    }

    /** Creates a note written in the app. Returns null if a note with the same content exists. */
    fun create(text: String, contentSha256: String, now: String, timeZone: String): Long? =
        CaptureStore(db).insertReturningId(
            CaptureRecord(UUID.randomUUID(), contentSha256, now, timeZone, SOURCE_NOTE, null, text),
        )

    /**
     * Replaces the text of a note. Returns false if another note already has that content.
     * [textSha256] is the hash of the text alone; for a note with a path the stored hash also
     * covers the path (see [contentHash]), and that is worked out here so no caller can forget it.
     */
    fun updateText(id: Long, text: String, textSha256: String, now: String): Boolean {
        val contentSha256 = pathOf(id)?.let { contentHash(text, it) } ?: textSha256
        val taken = db.rawQuery(
            "SELECT count(*) FROM capture WHERE content_sha256 = ? AND id != ?", contentSha256 as Any, id as Any,
        ).use { c -> c.moveToFirst(); c.getLong(0) > 0 }
        if (taken) return false
        // The title is cleared so it is derived again from the new text.
        db.execSQL(
            "UPDATE capture SET text = ?, content_sha256 = ?, updated_at = ?, title = NULL WHERE id = ?",
            arrayOf<Any>(text, contentSha256, now, id),
        )
        return true
    }

    /**
     * Stores what was read from a note's text: its title if it has none, its tags, its links,
     * and the aliases and properties of its front matter.
     */
    fun setMarkup(
        id: Long,
        title: String,
        tags: List<String>,
        links: List<NoteLink>,
        aliases: List<String> = emptyList(),
        properties: List<NoteProperty> = emptyList(),
    ) {
        db.beginTransaction()
        try {
            db.execSQL("UPDATE capture SET title = COALESCE(title, ?) WHERE id = ?", arrayOf<Any>(title, id))
            db.execSQL("DELETE FROM capture_tag WHERE capture_id = ?", arrayOf<Any>(id))
            for (tag in tags.distinctBy { it.lowercase() }) {
                db.execSQL("INSERT OR IGNORE INTO tag(name) VALUES (?)", arrayOf<Any>(tag))
                db.execSQL(
                    "INSERT OR IGNORE INTO capture_tag(capture_id, tag_id) SELECT ?, id FROM tag WHERE name = ?",
                    arrayOf<Any>(id, tag),
                )
            }
            db.execSQL("DELETE FROM tag WHERE id NOT IN (SELECT tag_id FROM capture_tag)")
            db.execSQL("DELETE FROM link WHERE source_id = ?", arrayOf<Any>(id))
            for (link in links) {
                db.execSQL(
                    "INSERT OR IGNORE INTO link(source_id, target_title, anchor, embed) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(id, link.target, link.anchor, if (link.embed) 1 else 0),
                )
            }
            db.execSQL("DELETE FROM alias WHERE capture_id = ?", arrayOf<Any>(id))
            for (alias in aliases.distinctBy { it.lowercase() }) {
                db.execSQL("INSERT OR IGNORE INTO alias(capture_id, name) VALUES (?, ?)", arrayOf<Any>(id, alias))
            }
            db.execSQL("DELETE FROM property WHERE capture_id = ?", arrayOf<Any>(id))
            for (property in properties) {
                property.values.forEachIndexed { position, value ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO property(capture_id, key, type, value, number, position) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>(id, property.key, property.type, value, property.numbers.getOrNull(position), position),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun get(id: Long): Note? = db.rawQuery(
        "SELECT id, COALESCE(title, ''), text, captured_at, source, path FROM capture WHERE id = ?", id as Any,
    ).use { c ->
        if (!c.moveToFirst()) return null
        Note(
            c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), tagsOf(id),
            path = if (c.isNull(5)) null else c.getString(5),
            aliases = aliasesOf(id),
            properties = propertiesOf(id),
        )
    }

    fun aliasesOf(id: Long): List<String> = db.rawQuery(
        "SELECT name FROM alias WHERE capture_id = ? ORDER BY name", id as Any,
    ).use { c -> strings(c) }

    /** The properties of a note, in the order they are written; a list comes back as one property. */
    fun propertiesOf(id: Long): List<NoteProperty> = db.rawQuery(
        "SELECT key, type, value, number FROM property WHERE capture_id = ? ORDER BY rowid", id as Any,
    ).use { c ->
        val rows = generateSequence {
            if (c.moveToNext()) Triple(c.getString(0) to c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getDouble(3)) else null
        }.toList()
        rows.groupBy { it.first }.map { (key, values) -> NoteProperty(key.first, key.second, values.map { it.second }, values.map { it.third }) }
    }

    fun pathOf(id: Long): String? = db.rawQuery("SELECT path FROM capture WHERE id = ?", id as Any).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
    }

    /** Places a note in the folder tree. Returns false if another note already has that path. */
    fun setPath(id: Long, path: String?): Boolean {
        if (path != null) {
            val taken = db.rawQuery("SELECT count(*) FROM capture WHERE path = ? AND id != ?", path as Any, id as Any)
                .use { c -> c.moveToFirst(); c.getLong(0) > 0 }
            if (taken) return false
        }
        db.execSQL("UPDATE capture SET path = ? WHERE id = ?", arrayOf<Any?>(path, id))
        return true
    }

    /** Every folder that holds a note, with the number of notes in it and in its subfolders. */
    fun folders(): List<Pair<String, Int>> {
        val counts = sortedMapOf<String, Int>(String.CASE_INSENSITIVE_ORDER)
        db.rawQuery("SELECT path FROM capture WHERE path IS NOT NULL", emptyArray<String>()).use { c ->
            while (c.moveToNext()) {
                val parts = c.getString(0).split('/').dropLast(1)
                for (depth in 1..parts.size) counts.merge(parts.take(depth).joinToString("/"), 1, Int::plus)
            }
        }
        return counts.map { it.key to it.value }
    }

    /** Notes in [folder] and its subfolders, by path. */
    fun inFolder(folder: String, limit: Int): List<CaptureSummary> = summaries(
        "SELECT id, captured_at, source, text, title FROM capture " +
            "WHERE substr(path, 1, ?) = ? COLLATE NOCASE ORDER BY path COLLATE NOCASE LIMIT $limit",
        folder.length + 1, "$folder/",
    )

    fun tagsOf(id: Long): List<String> = db.rawQuery(
        "SELECT t.name FROM tag t JOIN capture_tag ct ON ct.tag_id = t.id WHERE ct.capture_id = ? ORDER BY t.name",
        id as Any,
    ).use { c -> strings(c) }

    /** Every tag with the number of notes that use it, most used first. */
    fun tags(): List<Pair<String, Int>> = db.rawQuery(
        "SELECT t.name, count(*) FROM tag t JOIN capture_tag ct ON ct.tag_id = t.id " +
            "GROUP BY t.id ORDER BY count(*) DESC, t.name",
        emptyArray<String>(),
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getInt(1) else null }.toList() }

    fun withTag(tag: String, limit: Int): List<CaptureSummary> = summaries(
        "SELECT c.id, c.captured_at, c.source, c.text, c.title FROM capture c " +
            "JOIN capture_tag ct ON ct.capture_id = c.id JOIN tag t ON t.id = ct.tag_id " +
            "WHERE t.name = ? ORDER BY COALESCE(c.updated_at, c.captured_at) DESC LIMIT $limit",
        tag,
    )

    /** Notes that link to this note, by its title, one of its aliases or the last part of its path. */
    fun backlinks(id: Long): List<CaptureSummary> = summaries(
        "SELECT DISTINCT c.id, c.captured_at, c.source, c.text, c.title FROM capture c " +
            "JOIN link l ON l.source_id = c.id WHERE c.id != ? AND l.target_title IN ($NAMES_OF) ORDER BY c.id DESC",
        id, id, id, id,
    )

    /** Notes this note links to, for the links whose target exists. */
    fun linkedFrom(id: Long): List<CaptureSummary> = summaries(
        "SELECT DISTINCT c.id, c.captured_at, c.source, c.text, c.title FROM capture c JOIN link l ON l.source_id = ? " +
            "WHERE c.id != l.source_id AND (l.target_title = c.title " +
            "OR l.target_title IN (SELECT name FROM alias WHERE capture_id = c.id) " +
            "OR (c.path IS NOT NULL AND l.target_title = replace(c.path, rtrim(c.path, replace(c.path, '/', '')), ''))) " +
            "ORDER BY c.title",
        id,
    )

    /**
     * Notes that name this note in their text without linking to it. Titles and aliases shorter
     * than [MIN_MENTION] characters are not searched: they match too much.
     */
    fun unlinkedMentions(id: Long, limit: Int = 20): List<CaptureSummary> {
        val names = (listOfNotNull(get(id)?.title) + aliasesOf(id)).map { it.trim() }.filter { it.length >= MIN_MENTION }
        // Each name as a quoted phrase, so nothing in a title can be read as search syntax.
        val phrases = names.map { name -> WORD.findAll(name).joinToString(" ") { it.value } }.filter { it.isNotBlank() }
        if (phrases.isEmpty()) return emptyList()
        return summaries(
            "SELECT DISTINCT c.id, c.captured_at, c.source, c.text, c.title FROM chunk_fts " +
                "JOIN chunk ch ON ch.id = chunk_fts.rowid JOIN capture c ON c.id = ch.capture_id " +
                "WHERE chunk_fts MATCH ? AND c.id != ? " +
                "AND c.id NOT IN (SELECT source_id FROM link WHERE target_title IN ($NAMES_OF)) " +
                "ORDER BY c.id DESC LIMIT $limit",
            phrases.joinToString(" OR ") { "\"$it\"" }, id, id, id, id,
        )
    }

    /** Notes that share at least one tag with this note, the most shared tags first. */
    fun sharingTags(id: Long, limit: Int): List<CaptureSummary> = summaries(
        "SELECT c.id, c.captured_at, c.source, c.text, c.title FROM capture c " +
            "JOIN capture_tag ct ON ct.capture_id = c.id " +
            "WHERE ct.tag_id IN (SELECT tag_id FROM capture_tag WHERE capture_id = ?) AND c.id != ? " +
            "GROUP BY c.id ORDER BY count(*) DESC, c.id DESC LIMIT $limit",
        id, id,
    )

    /**
     * The note a `[[name]]` points to: first by title, then by alias, then by the last part of
     * its path, which is how Obsidian resolves a link.
     */
    fun findByTitle(title: String): Long? {
        val name = title.trim()
        if (name.isEmpty()) return null
        return first("SELECT id FROM capture WHERE title = ? COLLATE NOCASE ORDER BY id LIMIT 1", name)
            ?: first("SELECT capture_id FROM alias WHERE name = ? ORDER BY capture_id LIMIT 1", name)
            ?: first(
                "SELECT id FROM capture WHERE path = ? COLLATE NOCASE OR substr(path, -?) = ? COLLATE NOCASE ORDER BY id LIMIT 1",
                name, name.length + 1, "/$name",
            )
    }

    private fun first(sql: String, vararg arguments: Any): Long? =
        db.rawQuery(sql, *arguments).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** Deletes a note with its tags, links and search index. */
    fun delete(id: Long) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM chunk_vec WHERE rowid IN (SELECT id FROM chunk WHERE capture_id = ?)", arrayOf<Any>(id))
            db.execSQL(
                "INSERT INTO chunk_fts(chunk_fts, rowid, text) SELECT 'delete', id, text FROM chunk WHERE capture_id = ?",
                arrayOf<Any>(id),
            )
            db.execSQL("DELETE FROM chunk WHERE capture_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM capture_tag WHERE capture_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM tag WHERE id NOT IN (SELECT tag_id FROM capture_tag)")
            db.execSQL("DELETE FROM link WHERE source_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM alias WHERE capture_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM property WHERE capture_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM brain_pin WHERE capture_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM capture WHERE id = ?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun summaries(sql: String, vararg arguments: Any): List<CaptureSummary> = db.rawQuery(sql, *arguments).use { c ->
        generateSequence {
            if (c.moveToNext()) CaptureSummary(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)) else null
        }.toList()
    }

    private fun strings(c: android.database.Cursor): List<String> =
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()

    companion object {
        const val SOURCE_NOTE = "note"

        /**
         * The hash that makes a note unique. A note with a [path] is hashed with it, so two
         * files of a vault with the same text are two notes; a note without a path is hashed by
         * its text alone, which is what catches the same text shared twice.
         */
        fun contentHash(text: String, path: String?): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest((if (path == null) text else path + "\n" + text).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Notes brought in from an Obsidian vault folder. */
        const val SOURCE_VAULT = "vault"
        private const val MIN_MENTION = 4
        private val WORD = Regex("[\\p{L}\\p{N}]+")

        /**
         * A subquery with every name a link can use for one note: its title, its aliases and
         * the last part of its path. It takes the note's id three times.
         */
        private const val NAMES_OF =
            "SELECT title FROM capture WHERE id = ? AND title IS NOT NULL " +
                "UNION SELECT name FROM alias WHERE capture_id = ? " +
                "UNION SELECT replace(path, rtrim(path, replace(path, '/', '')), '') FROM capture WHERE id = ? AND path IS NOT NULL"
    }
}
