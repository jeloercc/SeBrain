package com.sebrain.core.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Logical vault snapshot. It excludes derived chunks, FTS rows and vectors; those
 * are rebuilt after restore. Callers must encrypt the returned bytes before writing.
 *
 * Format version 2 (approved 2026-11-12) adds the saved conversations after the notes.
 * Version 1 snapshots are still read; they simply have no conversations.
 */
object VaultBackupSnapshot {
    private val MAGIC = "SBSN".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION = 2
    private const val FIRST_VERSION = 1
    private const val MAX_RECORDS = 1_000_000
    private const val MAX_FIELD_BYTES = 16 * 1024 * 1024

    data class Record(
        val capture: CaptureRecord,
        val title: String?,
        val updatedAt: String?,
        val tags: List<String>,
        val links: List<String>,
    )

    data class Message(val fromUser: Boolean, val text: String, val createdAt: String)

    data class Conversation(
        val title: String,
        val createdAt: String,
        val updatedAt: String,
        val summary: String?,
        /** How many messages from the start the summary covers. */
        val summarized: Int,
        val messages: List<Message>,
    )

    class Snapshot(val records: List<Record>, val conversations: List<Conversation>)

    private fun exportConversations(db: SQLiteDatabase): List<Conversation> {
        val store = ConversationStore(db)
        return db.rawQuery(
            "SELECT id, title, created_at, updated_at FROM conversation ORDER BY id", emptyArray<String>(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val messages = db.rawQuery(
                        "SELECT from_user, text, created_at FROM message WHERE conversation_id = ? ORDER BY id", id as Any,
                    ).use { m -> buildList { while (m.moveToNext()) add(Message(m.getInt(0) == 1, m.getString(1), m.getString(2))) } }
                    val summary = store.summary(id)
                    add(Conversation(c.getString(1), c.getString(2), c.getString(3), summary?.first, summary?.second ?: 0, messages))
                }
            }
        }
    }

    private fun restoreConversations(db: SQLiteDatabase, conversations: List<Conversation>) {
        val store = ConversationStore(db)
        for (conversation in conversations) {
            val id = store.create(conversation.title, conversation.createdAt)
            conversation.messages.forEach { store.add(id, it.fromUser, it.text, it.createdAt) }
            if (conversation.summary != null) store.setSummary(id, conversation.summary, conversation.summarized)
            db.execSQL("UPDATE conversation SET updated_at = ? WHERE id = ?", arrayOf<Any>(conversation.updatedAt, id))
        }
    }

    fun export(db: SQLiteDatabase): ByteArray {
        val records = mutableListOf<Record>()
        db.rawQuery(
            "SELECT segment_id, content_sha256, captured_at, time_zone, source, subject, text, title, updated_at " +
                "FROM capture ORDER BY id",
            emptyArray<String>(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val tags = db.rawQuery(
                    "SELECT t.name FROM tag t JOIN capture_tag ct ON ct.tag_id = t.id " +
                        "WHERE ct.capture_id = ? ORDER BY t.name",
                    id as Any,
                ).use { c -> strings(c) }
                val links = db.rawQuery(
                    "SELECT target_title FROM link WHERE source_id = ? ORDER BY target_title",
                    id as Any,
                ).use { c -> strings(c) }
                records += Record(
                    CaptureRecord(
                        UUID.fromString(cursor.getString(0)),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        if (cursor.isNull(5)) null else cursor.getString(5),
                        cursor.getString(6),
                    ),
                    if (cursor.isNull(7)) null else cursor.getString(7),
                    if (cursor.isNull(8)) null else cursor.getString(8),
                    tags,
                    links,
                )
            }
        }
        return encode(records, exportConversations(db))
    }

    /**
     * Restores only into an empty database. Derived search data must be rebuilt separately.
     * Returns the number of notes restored.
     */
    fun restore(db: SQLiteDatabase, snapshot: ByteArray): Int {
        val decoded = decode(snapshot)
        val records = decoded.records
        val count = CaptureStore(db).count()
        check(count == 0L) { "Restore requires an empty vault" }
        db.beginTransaction()
        try {
            for (record in records) {
                val id = CaptureStore(db).insertReturningId(record.capture)
                    ?: error("Could not restore capture ${record.capture.segmentId}")
                if (record.title != null || record.updatedAt != null) {
                    db.execSQL(
                        "UPDATE capture SET title = ?, updated_at = ? WHERE id = ?",
                        arrayOf<Any?>(record.title, record.updatedAt, id),
                    )
                }
                for (tag in record.tags.distinctBy { it.lowercase() }) {
                    db.execSQL("INSERT OR IGNORE INTO tag(name) VALUES (?)", arrayOf<Any>(tag))
                    db.execSQL(
                        "INSERT OR IGNORE INTO capture_tag(capture_id, tag_id) SELECT ?, id FROM tag WHERE name = ?",
                        arrayOf<Any>(id, tag),
                    )
                }
                for (target in record.links.distinctBy { it.lowercase() }) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO link(source_id, target_title) VALUES (?, ?)",
                        arrayOf<Any>(id, target),
                    )
                }
            }
            restoreConversations(db, decoded.conversations)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return records.size
    }

    /** [version] exists so a test can write the old format; the app always writes the current one. */
    internal fun encode(records: List<Record>, conversations: List<Conversation> = emptyList(), version: Int = VERSION): ByteArray {
        require(records.size <= MAX_RECORDS) { "Too many backup records" }
        require(conversations.size <= MAX_RECORDS) { "Too many backup conversations" }
        require(version in FIRST_VERSION..VERSION) { "Unsupported snapshot version" }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeInt(version)
            data.writeInt(records.size)
            records.forEach { record ->
                writeString(data, record.capture.segmentId.toString())
                writeString(data, record.capture.contentSha256)
                writeString(data, record.capture.capturedAt)
                writeString(data, record.capture.timeZone)
                writeString(data, record.capture.source)
                writeNullable(data, record.capture.subject)
                writeString(data, record.capture.text)
                writeNullable(data, record.title)
                writeNullable(data, record.updatedAt)
                writeStrings(data, record.tags)
                writeStrings(data, record.links)
            }
            if (version >= 2) {
                data.writeInt(conversations.size)
                conversations.forEach { conversation ->
                    writeString(data, conversation.title)
                    writeString(data, conversation.createdAt)
                    writeString(data, conversation.updatedAt)
                    writeNullable(data, conversation.summary)
                    data.writeInt(conversation.summarized)
                    require(conversation.messages.size <= MAX_RECORDS) { "Too many backup messages" }
                    data.writeInt(conversation.messages.size)
                    conversation.messages.forEach { message ->
                        data.writeBoolean(message.fromUser)
                        writeString(data, message.text)
                        writeString(data, message.createdAt)
                    }
                }
            }
        }
        return output.toByteArray()
    }

    internal fun decode(bytes: ByteArray): Snapshot {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val magic = ByteArray(MAGIC.size).also(data::readFully)
            require(magic.contentEquals(MAGIC)) { "Bad snapshot magic" }
            val version = data.readInt()
            require(version in FIRST_VERSION..VERSION) { "Unsupported snapshot version" }
            val count = data.readInt()
            require(count in 0..MAX_RECORDS) { "Invalid snapshot record count" }
            val records = buildList(count) {
                repeat(count) {
                    val capture = CaptureRecord(
                        UUID.fromString(readString(data)),
                        readString(data),
                        readString(data),
                        readString(data),
                        readString(data),
                        readNullable(data),
                        readString(data),
                    )
                    add(Record(capture, readNullable(data), readNullable(data), readStrings(data), readStrings(data)))
                }
            }
            if (version < 2) return Snapshot(records, emptyList())
            val conversationCount = data.readInt()
            require(conversationCount in 0..MAX_RECORDS) { "Invalid snapshot conversation count" }
            val conversations = List(conversationCount) {
                val title = readString(data)
                val createdAt = readString(data)
                val updatedAt = readString(data)
                val summary = readNullable(data)
                val summarized = data.readInt()
                val messageCount = data.readInt()
                require(messageCount in 0..MAX_RECORDS) { "Invalid snapshot message count" }
                val messages = List(messageCount) { Message(data.readBoolean(), readString(data), readString(data)) }
                Conversation(title, createdAt, updatedAt, summary, summarized, messages)
            }
            return Snapshot(records, conversations)
        }
    }

    private fun writeStrings(data: DataOutputStream, values: List<String>) {
        require(values.size <= MAX_RECORDS) { "Too many snapshot values" }
        data.writeInt(values.size)
        values.forEach { writeString(data, it) }
    }

    private fun writeNullable(data: DataOutputStream, value: String?) {
        data.writeBoolean(value != null)
        if (value != null) writeString(data, value)
    }

    private fun writeString(data: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FIELD_BYTES) { "Snapshot field is too large" }
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    private fun readStrings(data: DataInputStream): List<String> {
        val count = data.readInt()
        require(count in 0..MAX_RECORDS) { "Invalid snapshot list size" }
        return List(count) { readString(data) }
    }

    private fun readNullable(data: DataInputStream): String? =
        if (data.readBoolean()) readString(data) else null

    private fun readString(data: DataInputStream): String {
        val length = data.readInt()
        require(length in 0..MAX_FIELD_BYTES) { "Invalid snapshot field length" }
        return ByteArray(length).also(data::readFully).toString(StandardCharsets.UTF_8)
    }

    private fun strings(cursor: android.database.Cursor): List<String> =
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
}
