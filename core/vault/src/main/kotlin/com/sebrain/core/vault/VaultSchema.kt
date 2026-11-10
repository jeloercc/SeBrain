package com.sebrain.core.vault

import net.zetetic.database.sqlcipher.SQLiteDatabase

/** Creates and upgrades the vault schema. The version is stored in `PRAGMA user_version`. */
internal object VaultSchema {
    const val VERSION = 2

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
}
