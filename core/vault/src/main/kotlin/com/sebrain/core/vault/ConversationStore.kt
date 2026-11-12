package com.sebrain.core.vault

import net.zetetic.database.sqlcipher.SQLiteDatabase

data class ConversationSummary(
    val id: Long,
    val title: String,
    val updatedAt: String,
    val messages: Int,
    /** The text that matched, when the conversation was found by search. */
    val snippet: String? = null,
)

/** A message found by search, with the conversation it belongs to. */
data class MessageHit(
    val conversationId: Long,
    val title: String,
    val updatedAt: String,
    val fromUser: Boolean,
    val text: String,
)

data class StoredMessage(val id: Long, val fromUser: Boolean, val text: String)

/** Saved chat conversations. Use it inside [VaultSession.withDatabase]. */
class ConversationStore(private val db: SQLiteDatabase) {
    fun create(title: String, now: String): Long = db.compileStatement(
        "INSERT INTO conversation(title, created_at, updated_at) VALUES (?, ?, ?)",
    ).use { statement ->
        statement.bindString(1, title.ifBlank { "Conversation" }.take(TITLE_LENGTH))
        statement.bindString(2, now)
        statement.bindString(3, now)
        statement.executeInsert()
    }

    fun add(conversationId: Long, fromUser: Boolean, text: String, now: String): Long {
        val id = db.compileStatement(
            "INSERT INTO message(conversation_id, from_user, text, created_at) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.bindLong(1, conversationId)
            statement.bindLong(2, if (fromUser) 1 else 0)
            statement.bindString(3, text)
            statement.bindString(4, now)
            statement.executeInsert()
        }
        db.execSQL("INSERT INTO message_fts(rowid, text) VALUES (?, ?)", arrayOf<Any>(id, text))
        db.execSQL("UPDATE conversation SET updated_at = ? WHERE id = ?", arrayOf<Any>(now, conversationId))
        return id
    }

    /**
     * Messages that contain words of [query], best match first. [exclude] leaves out one
     * conversation, usually the open one. Short words are ignored: "de", "the" and the like
     * match everything and say nothing.
     */
    fun search(query: String, limit: Int, exclude: Long? = null): List<MessageHit> {
        val words = WORD.findAll(query).map { it.value }.filter { it.length >= MIN_WORD }.distinct().toList()
        if (words.isEmpty()) return emptyList()
        val match = words.joinToString(" OR ") { "\"$it\"" }
        return db.rawQuery(
            "SELECT c.id, c.title, c.updated_at, m.from_user, m.text FROM message_fts " +
                "JOIN message m ON m.id = message_fts.rowid JOIN conversation c ON c.id = m.conversation_id " +
                "WHERE message_fts MATCH ? AND c.id != ? ORDER BY bm25(message_fts), m.id DESC LIMIT $limit",
            match, exclude ?: -1L,
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) MessageHit(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4)) else null
            }.toList()
        }
    }

    /** Conversations with a message that matches [query], each once, with its best matching text. */
    fun searchConversations(query: String, limit: Int = 50): List<ConversationSummary> =
        search(query, limit * 4).distinctBy { it.conversationId }.take(limit).map { hit ->
            ConversationSummary(hit.conversationId, hit.title, hit.updatedAt, count(hit.conversationId), hit.text)
        }

    private fun count(conversationId: Long): Int = db.rawQuery(
        "SELECT count(*) FROM message WHERE conversation_id = ?", conversationId as Any,
    ).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Most recently used first. */
    fun list(limit: Int = 100): List<ConversationSummary> = db.rawQuery(
        "SELECT c.id, c.title, c.updated_at, (SELECT count(*) FROM message m WHERE m.conversation_id = c.id) " +
            "FROM conversation c ORDER BY c.updated_at DESC, c.id DESC LIMIT $limit",
        emptyArray<String>(),
    ).use { c ->
        generateSequence {
            if (c.moveToNext()) ConversationSummary(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)) else null
        }.toList()
    }

    fun messages(conversationId: Long): List<StoredMessage> = db.rawQuery(
        "SELECT id, from_user, text FROM message WHERE conversation_id = ? ORDER BY id",
        conversationId as Any,
    ).use { c ->
        generateSequence { if (c.moveToNext()) StoredMessage(c.getLong(0), c.getInt(1) == 1, c.getString(2)) else null }.toList()
    }

    /** Deletes the conversation and its messages. Messages are deleted explicitly, so this does not depend on foreign keys being on. */
    fun delete(conversationId: Long) {
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO message_fts(message_fts, rowid, text) SELECT 'delete', id, text FROM message WHERE conversation_id = ?",
                arrayOf<Any>(conversationId),
            )
            db.execSQL("DELETE FROM message WHERE conversation_id = ?", arrayOf<Any>(conversationId))
            db.execSQL("DELETE FROM conversation WHERE id = ?", arrayOf<Any>(conversationId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val TITLE_LENGTH = 80
        const val MIN_WORD = 4
        val WORD = Regex("[\\p{L}\\p{N}]+")
    }
}
