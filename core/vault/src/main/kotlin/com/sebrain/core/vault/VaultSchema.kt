package com.sebrain.core.vault

import net.zetetic.database.sqlcipher.SQLiteDatabase

/** Creates and upgrades the vault schema. The version is stored in `PRAGMA user_version`. */
internal object VaultSchema {
    const val VERSION = 1

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
}
