package com.sebrain.core.vault

import net.zetetic.database.sqlcipher.SQLiteDatabase

data class ConversationSummary(val id: Long, val title: String, val updatedAt: String, val messages: Int)

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
        db.execSQL("UPDATE conversation SET updated_at = ? WHERE id = ?", arrayOf<Any>(now, conversationId))
        return id
    }

    /** Replaces the text of a message, for example when a streamed reply is complete. */
    fun updateText(messageId: Long, text: String) {
        db.execSQL("UPDATE message SET text = ? WHERE id = ?", arrayOf<Any>(text, messageId))
    }

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
            db.execSQL("DELETE FROM message WHERE conversation_id = ?", arrayOf<Any>(conversationId))
            db.execSQL("DELETE FROM conversation WHERE id = ?", arrayOf<Any>(conversationId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val TITLE_LENGTH = 80
    }
}
