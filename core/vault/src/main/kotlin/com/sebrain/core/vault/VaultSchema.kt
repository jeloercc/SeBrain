package com.sebrain.core.vault

import net.zetetic.database.sqlcipher.SQLiteDatabase

/** Creates and upgrades the vault schema. The version is stored in `PRAGMA user_version`. */
internal object VaultSchema {
    const val VERSION = 7

    /** Dimensions of the stored embeddings (EmbeddingGemma, Matryoshka at 256). */
    const val EMBEDDING_DIMENSIONS = 256

    fun migrate(db: SQLiteDatabase) {
        val current = db.rawQuery("PRAGMA user_version", emptyArray<String>()).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        check(current <= VERSION) { "Vault schema $current is newer than this app supports ($VERSION)" }
        if (current == VERSION) return
        db.beginTransaction()
        try {
            if (current < 1) createV1(db)
            if (current < 2) upgradeToV2(db)
            if (current < 3) upgradeToV3(db)
            if (current < 4) upgradeToV4(db)
            if (current < 5) upgradeToV5(db)
            if (current < 6) upgradeToV6(db)
            if (current < 7) upgradeToV7(db)
            db.execSQL("PRAGMA user_version = $VERSION")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createV1(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE capture(
                id INTEGER PRIMARY KEY,
                segment_id TEXT NOT NULL UNIQUE,
                content_sha256 TEXT NOT NULL UNIQUE,
                captured_at TEXT NOT NULL,
                time_zone TEXT NOT NULL,
                source TEXT NOT NULL,
                subject TEXT,
                text TEXT NOT NULL,
                extraction_status TEXT NOT NULL DEFAULT 'pending',
                extraction_attempts INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    /** Search index: chunks of each capture, with full text and vectors. */
    private fun upgradeToV2(db: SQLiteDatabase) {
        // pending: no chunks yet. text: chunks and full text. indexed: vectors too.
        db.execSQL("ALTER TABLE capture ADD COLUMN index_status TEXT NOT NULL DEFAULT 'pending'")
        db.execSQL(
            """
            CREATE TABLE chunk(
                id INTEGER PRIMARY KEY,
                capture_id INTEGER NOT NULL REFERENCES capture(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                text TEXT NOT NULL,
                UNIQUE(capture_id, position)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE chunk_fts USING fts5(" +
                "text, content='chunk', content_rowid='id', tokenize='unicode61 remove_diacritics 2')",
        )
        db.execSQL("CREATE VIRTUAL TABLE chunk_vec USING vec0(embedding float[$EMBEDDING_DIMENSIONS])")
    }

    /** Notes like Obsidian: every capture is a note with a title, tags and links to other notes. */
    private fun upgradeToV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE capture ADD COLUMN title TEXT")
        db.execSQL("ALTER TABLE capture ADD COLUMN updated_at TEXT")
        db.execSQL("CREATE TABLE tag(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE)")
        db.execSQL(
            "CREATE TABLE capture_tag(capture_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY(capture_id, tag_id))",
        )
        db.execSQL("CREATE INDEX capture_tag_by_tag ON capture_tag(tag_id)")
        // A link points to a title, like [[Title]] in Obsidian, so it can exist before its target does.
        db.execSQL(
            "CREATE TABLE link(source_id INTEGER NOT NULL, target_title TEXT NOT NULL COLLATE NOCASE, " +
                "PRIMARY KEY(source_id, target_title))",
        )
        db.execSQL("CREATE INDEX link_by_target ON link(target_title)")
    }

    /**
     * Chat conversations, approved 2026-11-12. They live in the vault so they are encrypted with
     * the vault key, and they are not chunked, so they never appear in note search. Not part of
     * the backup snapshot yet.
     */
    private fun upgradeToV4(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE conversation(id INTEGER PRIMARY KEY, title TEXT NOT NULL, " +
                "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE message(id INTEGER PRIMARY KEY, " +
                "conversation_id INTEGER NOT NULL REFERENCES conversation(id) ON DELETE CASCADE, " +
                "from_user INTEGER NOT NULL, text TEXT NOT NULL, created_at TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX message_by_conversation ON message(conversation_id, id)")
    }

    /**
     * Search over past conversations and room for a conversation summary, approved 2026-11-12.
     * `message_fts` indexes `message.text` the way `chunk_fts` indexes chunks; the code that
     * writes messages keeps it in step. Messages written under v4 are indexed here.
     */
    private fun upgradeToV5(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE message_fts USING fts5(" +
                "text, content='message', content_rowid='id', tokenize='unicode61 remove_diacritics 2')",
        )
        db.execSQL("INSERT INTO message_fts(rowid, text) SELECT id, text FROM message")
        db.execSQL("ALTER TABLE conversation ADD COLUMN summary TEXT")
        // The id of the last message the summary covers.
        db.execSQL("ALTER TABLE conversation ADD COLUMN summarized_until INTEGER")
    }

    /**
     * Obsidian's data model and brains, approved 2026-11-12 (docs/brains, phases 2 and 3).
     *
     * A note gains a place in the folder tree. Its properties and aliases, read from its front
     * matter, get tables of their own so they can be filtered. A link remembers which part of
     * the target it points to and whether it embeds it. The brain tables are created here so
     * phase 3 needs no second migration; nothing writes to them yet.
     *
     * `content_sha256` stays unique, and for a note with a path it now covers the path too, so
     * two files with the same text can both be imported. That rule lives in the code that
     * computes the hash; no stored hash changes here, because no note has a path yet.
     */
    private fun upgradeToV6(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE capture ADD COLUMN path TEXT")
        db.execSQL("CREATE UNIQUE INDEX capture_by_path ON capture(path) WHERE path IS NOT NULL")
        db.execSQL(
            "CREATE TABLE property(capture_id INTEGER NOT NULL, key TEXT NOT NULL COLLATE NOCASE, type TEXT NOT NULL, " +
                "value TEXT NOT NULL, number REAL, position INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(capture_id, key, position))",
        )
        db.execSQL("CREATE INDEX property_by_key ON property(key, value)")
        db.execSQL(
            "CREATE TABLE alias(capture_id INTEGER NOT NULL, name TEXT NOT NULL COLLATE NOCASE, PRIMARY KEY(capture_id, name))",
        )
        db.execSQL("CREATE INDEX alias_by_name ON alias(name)")

        // The link table is rebuilt: its primary key gains the anchor and the embed flag.
        db.execSQL(
            "CREATE TABLE link_v6(source_id INTEGER NOT NULL, target_title TEXT NOT NULL COLLATE NOCASE, " +
                "anchor TEXT NOT NULL DEFAULT '', embed INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(source_id, target_title, anchor, embed))",
        )
        db.execSQL("INSERT INTO link_v6(source_id, target_title) SELECT source_id, target_title FROM link")
        db.execSQL("DROP TABLE link")
        db.execSQL("ALTER TABLE link_v6 RENAME TO link")
        db.execSQL("CREATE INDEX link_by_target ON link(target_title)")

        db.execSQL(
            "CREATE TABLE brain(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE, scope TEXT NOT NULL, " +
                "instructions TEXT NOT NULL DEFAULT '', model TEXT, egress TEXT NOT NULL DEFAULT 'device', " +
                "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE brain_pin(brain_id INTEGER NOT NULL, capture_id INTEGER NOT NULL, " +
                "position INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(brain_id, capture_id))",
        )
        db.execSQL("ALTER TABLE conversation ADD COLUMN brain_id INTEGER")

        // Notes that already start with front matter are indexed again: their properties and
        // aliases are read, and the block leaves their search chunks.
        db.execSQL("UPDATE capture SET index_status = 'pending' WHERE text LIKE '---%'")
    }

    /**
     * What the CognitionWorker extracts from a capture, approved 2026-11-20
     * (docs/proposal-cognition-worker-gaps.md, gap 1, option A). Derived data: it never changes
     * the note, can be deleted and rebuilt, and is not in the backup snapshot (a restored
     * capture is `pending` again and is extracted anew). Lists are JSON arrays of strings.
     * A failure keeps only its code in `capture.extraction_error`, never the model's output.
     */
    private fun upgradeToV7(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE extraction(capture_id INTEGER PRIMARY KEY REFERENCES capture(id) ON DELETE CASCADE, " +
                "people TEXT NOT NULL, dates TEXT NOT NULL, topics TEXT NOT NULL, tasks TEXT NOT NULL, " +
                "title TEXT NOT NULL, model TEXT NOT NULL, extracted_at TEXT NOT NULL)",
        )
        db.execSQL("ALTER TABLE capture ADD COLUMN extraction_error TEXT")
    }
}
