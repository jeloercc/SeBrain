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

/**
 * Notes, tags and links. Every capture is a note. Use it inside [VaultSession.withDatabase].
 *
 * With a [brain], every list, search and link stays inside that brain: the notes whose path
 * starts with the folder `brain/`. A link to a note of another brain resolves to nothing, as if
 * that note were not written yet. Without one, it is the universal brain and sees every note.
 */
class NoteStore(private val db: SQLiteDatabase, val brain: String? = null) {
    /** An SQL condition that keeps the notes of [brain]; [column] is the capture's path column. */
    private fun inBrain(column: String = "path"): String =
        if (brain == null) "1" else "substr($column, 1, ${brain.length + 1}) = '${(brain + "/").replace("'", "''")}' COLLATE NOCASE"

    /** The newest notes of the brain, for display. */
    fun recent(limit: Int): List<CaptureSummary> = summaries(
        "SELECT id, captured_at, source, text, title FROM capture WHERE ${inBrain()} " +
            "ORDER BY COALESCE(updated_at, captured_at) DESC, id DESC LIMIT $limit",
    )

    /**
     * How many notes of the brain contain [phrase] as whole words, answered by the full-text
     * index, so it stays one indexed query however large the vault is. 0 if it has no word.
     */
    fun notesMentioning(phrase: String): Int {
        val words = WORD.findAll(phrase).map { it.value }.toList()
        if (words.isEmpty()) return 0
        // One quoted FTS5 phrase made only of words, so the input is never read as FTS5 syntax.
        return db.rawQuery(
            "SELECT count(DISTINCT ch.capture_id) FROM chunk_fts JOIN chunk ch ON ch.id = chunk_fts.rowid " +
                "JOIN capture c ON c.id = ch.capture_id WHERE chunk_fts MATCH ? AND ${inBrain("c.path")}",
            "\"${words.joinToString(" ")}\"" as Any,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * The ids of up to [limit] notes of the brain that contain [phrase] as whole words, by the same
     * full-text query as [notesMentioning]. Empty if the phrase has no word.
     */
    fun idsMentioning(phrase: String, limit: Int): List<Long> {
        val words = WORD.findAll(phrase).map { it.value }.toList()
        if (words.isEmpty()) return emptyList()
        return db.rawQuery(
            "SELECT DISTINCT ch.capture_id FROM chunk_fts JOIN chunk ch ON ch.id = chunk_fts.rowid " +
                "JOIN capture c ON c.id = ch.capture_id WHERE chunk_fts MATCH ? AND ${inBrain("c.path")} LIMIT ?",
            "\"${words.joinToString(" ")}\"" as Any,
            limit as Any,
        ).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }
    }

    fun count(): Long = db.rawQuery("SELECT count(*) FROM capture WHERE ${inBrain()}", emptyArray<String>()).use { c ->
        c.moveToFirst()
        c.getLong(0)
    }

    /** The newest [limit] notes and every link between them, as indexes into the node list. */
    fun graph(limit: Int = 2000, sources: Set<String>? = null): NoteGraph {
        val nodes = ArrayList<NoteGraph.Node>()
        val byId = HashMap<Long, Int>()
        val byTitle = HashMap<String, Int>()
        val sourceClause = when {
            sources == null -> " WHERE ${inBrain()}"
            sources.isEmpty() -> " WHERE 0"
            else -> " WHERE ${inBrain()} AND source IN (${sources.joinToString { "?" }})"
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
        // A link may also name a note by one of its aliases, or by its file name: the last part
        // of its path. Titles win, then aliases, as in findByTitle.
        db.rawQuery("SELECT capture_id, name FROM alias").use { c ->
            while (c.moveToNext()) byId[c.getLong(0)]?.let { byTitle.putIfAbsent(c.getString(1).lowercase(), it) }
        }
        db.rawQuery("SELECT id, path FROM capture WHERE path IS NOT NULL").use { c ->
            while (c.moveToNext()) byId[c.getLong(0)]?.let { node ->
                val path = c.getString(1).lowercase()
                byTitle.putIfAbsent(path.substringAfterLast('/'), node)
                byTitle.putIfAbsent(path, node)
            }
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

    /**
     * The edges the graph ranking walks, inside the brain: a resolved link weighs 1; two notes
     * that share a tag are joined with 1 / (notes with that tag), so a tag used everywhere counts
     * little. Tags on more than [MAX_TAG_NOTES] notes are skipped.
     */
    fun rankEdges(limit: Int = 2000): List<GraphRank.Edge> {
        val graph = graph(limit)
        val edges = ArrayList<GraphRank.Edge>()
        for ((a, b) in graph.edges) {
            val from = graph.nodes[a].id ?: continue
            val to = graph.nodes[b].id ?: continue
            edges += GraphRank.Edge(from, to, 1.0)
        }
        val byTag = HashMap<Long, MutableList<Long>>()
        db.rawQuery(
            "SELECT ct.tag_id, ct.capture_id FROM capture_tag ct JOIN capture c ON c.id = ct.capture_id WHERE ${inBrain("c.path")}",
            emptyArray<String>(),
        ).use { c -> while (c.moveToNext()) byTag.getOrPut(c.getLong(0)) { ArrayList() } += c.getLong(1) }
        for (notes in byTag.values) {
            if (notes.size < 2 || notes.size > MAX_TAG_NOTES) continue
            val weight = 1.0 / notes.size
            for (i in notes.indices) for (j in i + 1 until notes.size) edges += GraphRank.Edge(notes[i], notes[j], weight)
        }
        return edges
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
        // The title is cleared so it is derived again from the new text, and what was extracted
        // from the old text is dropped so the CognitionWorker reads the new one.
        db.execSQL(
            "UPDATE capture SET text = ?, content_sha256 = ?, updated_at = ?, title = NULL, " +
                "extraction_status = 'pending', extraction_attempts = 0, extraction_error = NULL WHERE id = ?",
            arrayOf<Any>(text, contentSha256, now, id),
        )
        db.execSQL("DELETE FROM extraction WHERE capture_id = ?", arrayOf<Any>(id))
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

    /**
     * Every name a note goes by: its title and each alias, as (note id, title, name). Used to
     * find which note a message names and which notes a text mentions.
     */
    fun names(limit: Int = 5000): List<Triple<Long, String, String>> = db.rawQuery(
        "SELECT id, title, title FROM capture WHERE title IS NOT NULL AND title != '' AND ${inBrain()} " +
            "UNION ALL SELECT a.capture_id, c.title, a.name FROM alias a JOIN capture c ON c.id = a.capture_id " +
            "WHERE c.title IS NOT NULL AND ${inBrain("c.path")} LIMIT $limit",
        emptyArray<String>(),
    ).use { c -> generateSequence { if (c.moveToNext()) Triple(c.getLong(0), c.getString(1), c.getString(2)) else null }.toList() }

    /**
     * Link targets that no note answers to, by title, alias or file name, with how many notes
     * link to each. They are the concepts the vault names but has not written yet.
     */
    fun unresolvedLinks(limit: Int = 20): List<Pair<String, Int>> = db.rawQuery(
        "SELECT l.target_title, count(DISTINCT l.source_id) FROM link l JOIN capture s ON s.id = l.source_id " +
            "WHERE ${inBrain("s.path")} " +
            "AND NOT EXISTS (SELECT 1 FROM capture c WHERE c.title = l.target_title AND ${inBrain("c.path")}) " +
            "AND NOT EXISTS (SELECT 1 FROM alias a JOIN capture c ON c.id = a.capture_id WHERE a.name = l.target_title AND ${inBrain("c.path")}) " +
            "AND NOT EXISTS (SELECT 1 FROM capture c WHERE c.path IS NOT NULL AND ${inBrain("c.path")} AND (c.path = l.target_title " +
            "OR substr(c.path, -(length(l.target_title) + 1)) = '/' || l.target_title)) " +
            "GROUP BY l.target_title ORDER BY count(DISTINCT l.source_id) DESC, l.target_title LIMIT $limit",
        emptyArray<String>(),
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getInt(1) else null }.toList() }

    /** The note at [path] in the folder tree, if there is one. */
    fun idAtPath(path: String): Long? = first("SELECT id FROM capture WHERE path = ? COLLATE NOCASE LIMIT 1", path)

    /** Notes with an id above [afterId], oldest first, for walking the whole vault in pages. */
    fun page(afterId: Long, limit: Int): List<Note> = db.rawQuery(
        "SELECT id FROM capture WHERE id > ? AND ${inBrain()} ORDER BY id LIMIT $limit", afterId as Any,
    ).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }.mapNotNull(::get)

    /**
     * Places a note in the folder tree, or takes it out with null. Returns false if another note
     * already has that path, or would then have the same content hash. The hash follows the path
     * (see [contentHash]), so it is stored again with it.
     */
    fun setPath(id: Long, path: String?): Boolean {
        if (path != null) {
            val taken = db.rawQuery("SELECT count(*) FROM capture WHERE path = ? COLLATE NOCASE AND id != ?", path as Any, id as Any)
                .use { c -> c.moveToFirst(); c.getLong(0) > 0 }
            if (taken) return false
        }
        val text = db.rawQuery("SELECT text FROM capture WHERE id = ?", id as Any).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: return false
        val hash = contentHash(text, path)
        val clash = db.rawQuery("SELECT count(*) FROM capture WHERE content_sha256 = ? AND id != ?", hash as Any, id as Any)
            .use { c -> c.moveToFirst(); c.getLong(0) > 0 }
        if (clash) return false
        db.execSQL("UPDATE capture SET path = ?, content_sha256 = ? WHERE id = ?", arrayOf<Any?>(path, hash, id))
        return true
    }

    /** A free path for a new note titled [title] in [folder]: its file name, numbered if taken. */
    fun freePath(folder: String, title: String): String {
        val stem = title.trim().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim('.', ' ', '_').take(80).ifBlank { "Untitled" }
        var path = "$folder/$stem"
        var n = 2
        while (idAtPath(path) != null) path = "$folder/$stem (${n++})"
        return path
    }

    /** Every folder that holds a note, with the number of notes in it and in its subfolders. */
    fun folders(): List<Pair<String, Int>> {
        val counts = sortedMapOf<String, Int>(String.CASE_INSENSITIVE_ORDER)
        db.rawQuery("SELECT path FROM capture WHERE path IS NOT NULL AND ${inBrain()}", emptyArray<String>()).use { c ->
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
        "SELECT t.name, count(*) FROM tag t JOIN capture_tag ct ON ct.tag_id = t.id JOIN capture c ON c.id = ct.capture_id " +
            "WHERE ${inBrain("c.path")} GROUP BY t.id ORDER BY count(*) DESC, t.name",
        emptyArray<String>(),
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getInt(1) else null }.toList() }

    fun withTag(tag: String, limit: Int): List<CaptureSummary> = summaries(
        "SELECT c.id, c.captured_at, c.source, c.text, c.title FROM capture c " +
            "JOIN capture_tag ct ON ct.capture_id = c.id JOIN tag t ON t.id = ct.tag_id " +
            "WHERE t.name = ? AND ${inBrain("c.path")} ORDER BY COALESCE(c.updated_at, c.captured_at) DESC LIMIT $limit",
        tag,
    )

    /** Notes that link to this note, by its title, one of its aliases or the last part of its path. */
    fun backlinks(id: Long): List<CaptureSummary> = summaries(
        "SELECT DISTINCT c.id, c.captured_at, c.source, c.text, c.title FROM capture c " +
            "JOIN link l ON l.source_id = c.id WHERE c.id != ? AND l.target_title IN ($NAMES_OF) AND ${inBrain("c.path")} ORDER BY c.id DESC",
        id, id, id, id,
    )

    /** Notes this note links to, for the links whose target exists. */
    fun linkedFrom(id: Long): List<CaptureSummary> = summaries(
        "SELECT DISTINCT c.id, c.captured_at, c.source, c.text, c.title FROM capture c JOIN link l ON l.source_id = ? " +
            "WHERE c.id != l.source_id AND ${inBrain("c.path")} AND (l.target_title = c.title " +
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
                "WHERE chunk_fts MATCH ? AND c.id != ? AND ${inBrain("c.path")} " +
                "AND c.id NOT IN (SELECT source_id FROM link WHERE target_title IN ($NAMES_OF)) " +
                "ORDER BY c.id DESC LIMIT $limit",
            phrases.joinToString(" OR ") { "\"$it\"" }, id, id, id, id,
        )
    }

    /**
     * The note a `[[name]]` points to: first by title, then by alias, then by the last part of
     * its path, which is how Obsidian resolves a link.
     */
    fun findByTitle(title: String): Long? {
        val name = title.trim()
        if (name.isEmpty()) return null
        return first("SELECT id FROM capture WHERE title = ? COLLATE NOCASE AND ${inBrain()} ORDER BY id LIMIT 1", name)
            ?: first("SELECT a.capture_id FROM alias a JOIN capture c ON c.id = a.capture_id WHERE a.name = ? AND ${inBrain("c.path")} ORDER BY a.capture_id LIMIT 1", name)
            ?: first(
                "SELECT id FROM capture WHERE (path = ? COLLATE NOCASE OR substr(path, -?) = ? COLLATE NOCASE) AND ${inBrain()} ORDER BY id LIMIT 1",
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
            // Foreign keys are off, so the cascade of `extraction` does not run by itself.
            db.execSQL("DELETE FROM extraction WHERE capture_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM capture WHERE id = ?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- Memory by time, by property, and templates ----

    /**
     * Notes of the brain from [from] to [to] (both `YYYY-MM-DD`, included): written or edited
     * then, dated by a `date`, `created` or `day` property, or daily notes (a title or file name
     * that is the date). Newest first. Templates are not memory and are left out.
     */
    fun writtenBetween(from: String, to: String, limit: Int): List<CaptureSummary> = summaries(
        "SELECT c.id, c.captured_at, c.source, c.text, c.title FROM capture c WHERE ${inBrain("c.path")} AND ${notTemplate("c.path")} AND (" +
            "substr(c.captured_at, 1, 10) BETWEEN ? AND ? " +
            "OR substr(COALESCE(c.updated_at, ''), 1, 10) BETWEEN ? AND ? " +
            "OR (c.title GLOB '$DATE_GLOB' AND c.title BETWEEN ? AND ?) " +
            "OR (c.path GLOB '*$DATE_GLOB' AND substr(c.path, -10) BETWEEN ? AND ?) " +
            "OR EXISTS (SELECT 1 FROM property p WHERE p.capture_id = c.id AND p.type = 'date' " +
            "AND p.key IN ('date', 'created', 'day') AND substr(p.value, 1, 10) BETWEEN ? AND ?)) " +
            "ORDER BY COALESCE(c.updated_at, c.captured_at) DESC, c.id DESC LIMIT $limit",
        from, to, from, to, from, to, from, to, from, to,
    )

    /** One `key: value` of the brain's front matter, and how many notes have it. */
    data class PropertyValue(val key: String, val value: String, val notes: Int)

    /** The brain's property values, except dates and long texts, most used first. */
    fun propertyValues(limit: Int = MAX_PROPERTY_VALUES): List<PropertyValue> = db.rawQuery(
        "SELECT p.key, p.value, count(DISTINCT p.capture_id) FROM property p JOIN capture c ON c.id = p.capture_id " +
            "WHERE ${inBrain("c.path")} AND ${notTemplate("c.path")} AND p.type != 'date' AND length(p.value) BETWEEN 1 AND $MAX_PROPERTY_CHARS " +
            "GROUP BY p.key COLLATE NOCASE, p.value COLLATE NOCASE ORDER BY count(DISTINCT p.capture_id) DESC LIMIT $limit",
        emptyArray<String>(),
    ).use { c -> generateSequence { if (c.moveToNext()) PropertyValue(c.getString(0), c.getString(1), c.getInt(2)) else null }.toList() }

    /** A front-matter key of the brain whose values can be compared: a number or a date. */
    data class PropertyKey(val key: String, val type: String)

    /** The brain's number and date keys, for comparisons such as "priority above 2" or "due before Friday". */
    fun propertyKeys(): List<PropertyKey> = db.rawQuery(
        "SELECT DISTINCT p.key, p.type FROM property p JOIN capture c ON c.id = p.capture_id " +
            "WHERE p.type IN ('number', 'date') AND ${inBrain("c.path")} AND ${notTemplate("c.path")} LIMIT $MAX_PROPERTY_VALUES",
        emptyArray<String>(),
    ).use { c -> generateSequence { if (c.moveToNext()) PropertyKey(c.getString(0), c.getString(1)) else null }.toList() }

    /**
     * Notes of the brain whose [key] compares to [value] with [operator] (one of `<`, `<=`, `>`,
     * `>=`): as a number, or as a `YYYY-MM-DD` date when [isDate]. Returns the exact total and
     * the newest [limit] notes. The operator comes from a fixed list, never from the message.
     */
    fun compared(key: String, operator: String, value: String, isDate: Boolean, limit: Int): Pair<Int, List<CaptureSummary>> {
        require(operator in COMPARISONS) { "Unknown comparison" }
        val condition = if (isDate) "p.type = 'date' AND substr(p.value, 1, 10) $operator ?" else "p.number IS NOT NULL AND p.number $operator ?"
        val operand: Any = if (isDate) value else value.toDouble()
        val from = "FROM capture c JOIN property p ON p.capture_id = c.id WHERE p.key = ? AND $condition " +
            "AND ${inBrain("c.path")} AND ${notTemplate("c.path")}"
        val total = db.rawQuery("SELECT count(DISTINCT c.id) $from", key as Any, operand).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val notes = summaries(
            "SELECT DISTINCT c.id, c.captured_at, c.source, c.text, c.title $from ORDER BY COALESCE(c.updated_at, c.captured_at) DESC, c.id DESC LIMIT $limit",
            key, operand,
        )
        return total to notes
    }

    /** Notes of the brain whose property [key] has the value [value], ignoring case. Newest first. */
    fun withProperty(key: String, value: String, limit: Int): List<CaptureSummary> = summaries(
        "SELECT DISTINCT c.id, c.captured_at, c.source, c.text, c.title FROM capture c JOIN property p ON p.capture_id = c.id " +
            "WHERE p.key = ? AND p.value = ? COLLATE NOCASE AND ${inBrain("c.path")} AND ${notTemplate("c.path")} " +
            "ORDER BY COALESCE(c.updated_at, c.captured_at) DESC, c.id DESC LIMIT $limit",
        key, value,
    )

    /**
     * The notes of the vault's `Templates/` folder, as (name, text). As in Obsidian, a template
     * is named by its file, not its heading, which is often `# {{title}}`. They are structures
     * for new notes, shared by every brain, and never recalled as memory.
     */
    fun templates(limit: Int = MAX_TEMPLATES): List<Pair<String, String>> = db.rawQuery(
        "SELECT path, text FROM capture WHERE NOT ${notTemplate("path")} ORDER BY path LIMIT $limit",
        emptyArray<String>(),
    ).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0).substringAfterLast('/') to c.getString(1) else null }
            .filter { it.first.isNotBlank() }.toList()
    }

    private fun notTemplate(column: String) = "substr(COALESCE($column, ''), 1, ${TEMPLATES.length + 1}) != '$TEMPLATES/' COLLATE NOCASE"

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

        /** The top-level folder of note templates, as in Obsidian. It is not a brain and not memory. */
        const val TEMPLATES = "Templates"

        /** Whether a note at [path] is a template. */
        fun isTemplate(path: String?): Boolean = path != null && path.startsWith("$TEMPLATES/", ignoreCase = true)

        private const val DATE_GLOB = "[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]"
        private const val MAX_PROPERTY_VALUES = 2000
        private const val MAX_PROPERTY_CHARS = 60
        private const val MAX_TEMPLATES = 50
        private val COMPARISONS = setOf("<", "<=", ">", ">=")
        private const val MIN_MENTION = 4
        private const val MAX_TAG_NOTES = 50
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
