package com.sebrain.core.vault

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * A sub-brain: a top-level folder of the vault, seen on its own. [private] means only the
 * phone's model may be used in it, and its notes can never be sent to an external AI.
 */
data class Brain(val id: Long, val name: String, val private: Boolean, val notes: Int)

/**
 * The sub-brains of the vault. A brain is a top-level folder, so it can be imported from or
 * exported to Obsidian as a folder. Its settings live in the `brain` table of schema v6; a
 * folder that came in with an import gets its row the first time it is listed.
 */
class BrainRegistry(private val db: SQLiteDatabase) {
    /** Every sub-brain, by name, with how many notes it has. */
    fun list(now: String): List<Brain> {
        val counts = HashMap<String, Int>()
        db.rawQuery("SELECT path FROM capture WHERE path IS NOT NULL AND instr(path, '/') > 0", emptyArray<String>()).use { c ->
            while (c.moveToNext()) counts.merge(c.getString(0).substringBefore('/'), 1, Int::plus)
        }
        // Folders without a row, such as those of an imported vault, get one: external AI allowed.
        val known = rows().map { it.second.lowercase() }.toSet()
        counts.keys.filter { it.lowercase() !in known }.forEach { insert(it, private = false, now = now) }
        return rows().map { (id, name, private) ->
            Brain(id, name, private, counts.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: 0)
        }.sortedBy { it.name.lowercase() }
    }

    fun find(name: String): Brain? = rows().firstOrNull { it.second.equals(name, ignoreCase = true) }?.let { Brain(it.first, it.second, it.third, 0) }

    /** Creates a brain, or returns the one with that name. */
    fun create(name: String, private: Boolean, now: String): Brain {
        val clean = checkName(name)
        find(clean)?.let { return it }
        return Brain(insert(clean, private, now), clean, private, 0)
    }

    fun setPrivate(id: Long, private: Boolean, now: String) {
        db.execSQL("UPDATE brain SET egress = ?, updated_at = ? WHERE id = ?", arrayOf<Any>(if (private) DEVICE else EXTERNAL, now, id))
    }

    /** The names of the private brains, to keep their notes away from external models. */
    fun privateNames(): Set<String> = rows().filter { it.third }.map { it.second.lowercase() }.toSet()

    private fun rows(): List<Triple<Long, String, Boolean>> = db.rawQuery(
        "SELECT id, name, egress FROM brain ORDER BY name", emptyArray<String>(),
    ).use { c -> generateSequence { if (c.moveToNext()) Triple(c.getLong(0), c.getString(1), c.getString(2) == DEVICE) else null }.toList() }

    private fun insert(name: String, private: Boolean, now: String): Long = db.compileStatement(
        "INSERT INTO brain(name, scope, egress, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
    ).use { statement ->
        statement.bindString(1, name)
        statement.bindString(2, org.json.JSONObject().put("folder", name).toString())
        statement.bindString(3, if (private) DEVICE else EXTERNAL)
        statement.bindString(4, now)
        statement.bindString(5, now)
        statement.executeInsert()
    }

    companion object {
        const val DEVICE = "device"
        const val EXTERNAL = "external"
        private const val MAX_NAME = 60

        /** A brain's name is a folder name: no slash, no characters a file system refuses. */
        fun checkName(name: String): String {
            val clean = name.trim()
            require(clean.isNotEmpty() && clean.length <= MAX_NAME) { "A brain needs a name of up to $MAX_NAME characters" }
            require(clean.none { it in "/\\\\:*?\"<>|" } && !clean.startsWith(".")) { "A brain's name cannot contain / \\\\ : * ? \" < > | or start with a dot" }
            return clean
        }

        /** The brain a note path belongs to, or null for a note outside every brain. */
        fun of(path: String?): String? = path?.takeIf { '/' in it }?.substringBefore('/')
    }
}
