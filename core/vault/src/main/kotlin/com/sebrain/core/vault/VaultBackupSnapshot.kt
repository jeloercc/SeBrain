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
 * Version 3 adds each note's path in the folder tree; aliases and properties are not stored,
 * because they are read again from the note's text. Version 4 adds the brains' settings (above
 * all whether each one is private), their pins and the brain of each conversation; a backup
 * without them restores every brain as private (docs/design-vault-restore.md). Older versions
 * are still read.
 */
object VaultBackupSnapshot {
    private val MAGIC = "SBSN".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION = 4
    private const val BRAINS_VERSION = 4
    private const val FIRST_VERSION = 1
    private const val MAX_RECORDS = 1_000_000
    private const val MAX_FIELD_BYTES = 16 * 1024 * 1024

    data class Record(
        val capture: CaptureRecord,
        val title: String?,
        val updatedAt: String?,
        val tags: List<String>,
        val links: List<String>,
        val path: String? = null,
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
        /** The brain the conversation belongs to, by name; null for the universal brain. */
        val brain: String? = null,
    )

    /** A row of the `brain` table. [egress] is [BrainRegistry.DEVICE] for a private brain. */
    data class BrainSetting(
        val name: String,
        val scope: String,
        val instructions: String,
        val model: String?,
        val egress: String,
        val createdAt: String,
        val updatedAt: String,
    )

    /** A note pinned in a brain, by the brain's name and the note's segment id: row ids change on restore. */
    data class Pin(val brain: String, val segmentId: UUID, val position: Int)

    class Snapshot(
        val records: List<Record>,
        val conversations: List<Conversation>,
        val brains: List<BrainSetting> = emptyList(),
        val pins: List<Pin> = emptyList(),
        val version: Int = VERSION,
    )

    /** What a restore wrote. [brainsMadePrivate] counts the brains of an old backup, restored private. */
    data class Restored(val notes: Int, val conversations: Int, val brains: Int, val brainsMadePrivate: Int)

    private fun exportConversations(db: SQLiteDatabase): List<Conversation> {
        val store = ConversationStore(db)
        return db.rawQuery(
            "SELECT c.id, c.title, c.created_at, c.updated_at, b.name FROM conversation c LEFT JOIN brain b ON b.id = c.brain_id ORDER BY c.id",
            emptyArray<String>(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val messages = db.rawQuery(
                        "SELECT from_user, text, created_at FROM message WHERE conversation_id = ? ORDER BY id", id as Any,
                    ).use { m -> buildList { while (m.moveToNext()) add(Message(m.getInt(0) == 1, m.getString(1), m.getString(2))) } }
                    val summary = store.summary(id)
                    val brain = if (c.isNull(4)) null else c.getString(4)
                    add(Conversation(c.getString(1), c.getString(2), c.getString(3), summary?.first, summary?.second ?: 0, messages, brain))
                }
            }
        }
    }

    private fun exportBrains(db: SQLiteDatabase): List<BrainSetting> = db.rawQuery(
        "SELECT name, scope, instructions, model, egress, created_at, updated_at FROM brain ORDER BY id", emptyArray<String>(),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(BrainSetting(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), c.getString(4), c.getString(5), c.getString(6)))
            }
        }
    }

    private fun exportPins(db: SQLiteDatabase): List<Pin> = db.rawQuery(
        "SELECT b.name, c.segment_id, p.position FROM brain_pin p JOIN brain b ON b.id = p.brain_id " +
            "JOIN capture c ON c.id = p.capture_id ORDER BY p.brain_id, p.position",
        emptyArray<String>(),
    ).use { c -> buildList { while (c.moveToNext()) add(Pin(c.getString(0), UUID.fromString(c.getString(1)), c.getInt(2))) } }

    private fun restoreConversations(db: SQLiteDatabase, conversations: List<Conversation>) {
        val store = ConversationStore(db)
        for (conversation in conversations) {
            val id = store.create(conversation.title, conversation.createdAt)
            conversation.messages.forEach { store.add(id, it.fromUser, it.text, it.createdAt) }
            if (conversation.summary != null) store.setSummary(id, conversation.summary, conversation.summarized)
            db.execSQL("UPDATE conversation SET updated_at = ? WHERE id = ?", arrayOf<Any>(conversation.updatedAt, id))
            if (conversation.brain != null) {
                db.execSQL("UPDATE conversation SET brain_id = (SELECT id FROM brain WHERE name = ?) WHERE id = ?", arrayOf<Any>(conversation.brain, id))
            }
        }
    }

    /**
     * The brains' settings: as saved, from a v4 backup; from an older one, which did not save
     * them, every brain folder is made private, so no note becomes sendable to an external AI.
     * Returns how many brains were made private that way.
     */
    private fun restoreBrains(db: SQLiteDatabase, decoded: Snapshot, now: String): Int {
        if (decoded.version >= BRAINS_VERSION) {
            for (brain in decoded.brains) {
                db.execSQL(
                    "INSERT INTO brain(name, scope, instructions, model, egress, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(brain.name, brain.scope, brain.instructions, brain.model, brain.egress, brain.createdAt, brain.updatedAt),
                )
            }
            for (pin in decoded.pins) {
                db.execSQL(
                    "INSERT OR IGNORE INTO brain_pin(brain_id, capture_id, position) " +
                        "SELECT b.id, c.id, ? FROM brain b, capture c WHERE b.name = ? AND c.segment_id = ?",
                    arrayOf<Any>(pin.position, pin.brain, pin.segmentId.toString()),
                )
            }
            return 0
        }
        val folders = decoded.records.mapNotNull { BrainRegistry.of(it.path) }.distinctBy { it.lowercase() }
        val registry = BrainRegistry(db)
        folders.forEach { registry.create(it, private = true, now = now) }
        return folders.size
    }

    fun export(db: SQLiteDatabase): ByteArray {
        val records = mutableListOf<Record>()
        db.rawQuery(
            "SELECT segment_id, content_sha256, captured_at, time_zone, source, subject, text, title, updated_at, path, id " +
                "FROM capture ORDER BY id",
            emptyArray<String>(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                // The row id is the last column. It used to be read from column 0, the segment
                // UUID, so tags and links were exported for the wrong note.
                val id = cursor.getLong(10)
                val tags = db.rawQuery(
                    "SELECT t.name FROM tag t JOIN capture_tag ct ON ct.tag_id = t.id " +
                        "WHERE ct.capture_id = ? ORDER BY t.name",
                    id as Any,
                ).use { c -> strings(c) }
                val links = db.rawQuery(
                    "SELECT DISTINCT target_title FROM link WHERE source_id = ? ORDER BY target_title",
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
                    if (cursor.isNull(9)) null else cursor.getString(9),
                )
            }
        }
        return encode(records, exportConversations(db), brains = exportBrains(db), pins = exportPins(db))
    }

    /**
     * Restores only into an empty database. Derived search data must be rebuilt separately.
     * Returns the number of notes restored.
     */
    fun restore(db: SQLiteDatabase, snapshot: ByteArray, now: String = java.time.Instant.now().toString()): Restored {
        val decoded = decode(snapshot)
        val records = decoded.records
        val count = CaptureStore(db).count()
        check(count == 0L) { "Restore requires an empty vault" }
        db.beginTransaction()
        try {
            for (record in records) {
                val id = CaptureStore(db).insertReturningId(record.capture)
                    ?: error("Could not restore capture ${record.capture.segmentId}")
                if (record.path != null) db.execSQL("UPDATE capture SET path = ? WHERE id = ?", arrayOf<Any>(record.path, id))
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
            // Brains before conversations and pins, which point to them by name.
            val madePrivate = restoreBrains(db, decoded, now)
            restoreConversations(db, decoded.conversations)
            db.setTransactionSuccessful()
            return Restored(records.size, decoded.conversations.size, if (decoded.version >= BRAINS_VERSION) decoded.brains.size else madePrivate, madePrivate)
        } finally {
            db.endTransaction()
        }
    }

    /** [version] exists so a test can write the old format; the app always writes the current one. */
    internal fun encode(
        records: List<Record>,
        conversations: List<Conversation> = emptyList(),
        version: Int = VERSION,
        brains: List<BrainSetting> = emptyList(),
        pins: List<Pin> = emptyList(),
    ): ByteArray {
        require(records.size <= MAX_RECORDS) { "Too many backup records" }
        require(conversations.size <= MAX_RECORDS) { "Too many backup conversations" }
        require(brains.size <= MAX_RECORDS && pins.size <= MAX_RECORDS) { "Too many backup brains or pins" }
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
                if (version >= 3) writeNullable(data, record.path)
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
                    if (version >= BRAINS_VERSION) writeNullable(data, conversation.brain)
                }
            }
            if (version >= BRAINS_VERSION) {
                data.writeInt(brains.size)
                brains.forEach { brain ->
                    writeString(data, brain.name)
                    writeString(data, brain.scope)
                    writeString(data, brain.instructions)
                    writeNullable(data, brain.model)
                    writeString(data, brain.egress)
                    writeString(data, brain.createdAt)
                    writeString(data, brain.updatedAt)
                }
                data.writeInt(pins.size)
                pins.forEach { pin ->
                    writeString(data, pin.brain)
                    writeString(data, pin.segmentId.toString())
                    data.writeInt(pin.position)
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
                    val title = readNullable(data)
                    val updatedAt = readNullable(data)
                    val tags = readStrings(data)
                    val links = readStrings(data)
                    add(Record(capture, title, updatedAt, tags, links, if (version >= 3) readNullable(data) else null))
                }
            }
            if (version < 2) return Snapshot(records, emptyList(), version = version)
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
                val brain = if (version >= BRAINS_VERSION) readNullable(data) else null
                Conversation(title, createdAt, updatedAt, summary, summarized, messages, brain)
            }
            if (version < BRAINS_VERSION) return Snapshot(records, conversations, version = version)
            val brainCount = data.readInt()
            require(brainCount in 0..MAX_RECORDS) { "Invalid snapshot brain count" }
            val brains = List(brainCount) {
                BrainSetting(readString(data), readString(data), readString(data), readNullable(data), readString(data), readString(data), readString(data))
            }
            val pinCount = data.readInt()
            require(pinCount in 0..MAX_RECORDS) { "Invalid snapshot pin count" }
            val pins = List(pinCount) { Pin(readString(data), UUID.fromString(readString(data)), data.readInt()) }
            return Snapshot(records, conversations, brains, pins, version)
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
