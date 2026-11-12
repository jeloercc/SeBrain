package com.sebrain.core.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import java.io.File
import java.util.UUID
import kotlin.math.roundToLong
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchScaleBenchmarkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var wrapKey: WrapKey
    private lateinit var session: VaultSession

    @Before
    fun setUp() {
        dir = File(context.noBackupFilesDir, "search-scale-${System.nanoTime()}").apply { mkdirs() }
        wrapKey = WrapKey(alias = "test_scale_${System.nanoTime()}")
        session = VaultSession(context, File(dir, "vault.db"), WrappedDekStore(File(dir, "dek.wrapped"), wrapKey))
    }

    @After
    fun tearDown() {
        session.close()
        wrapKey.delete()
        dir.deleteRecursively()
    }

    @Test
    fun tenThousandNotesReportTextSearchLatency() {
        session.withDatabase { db ->
            db.beginTransaction()
            try {
                repeat(10_000) { index ->
                    val text = if (index % 100 == 0) {
                        "benchmark target note $index secure local knowledge"
                    } else {
                        "benchmark note $index unrelated local knowledge"
                    }
                    val id = CaptureStore(db).insertReturningId(
                        CaptureRecord(
                            UUID.randomUUID(),
                            UUID.randomUUID().toString(),
                            "2026-11-11T00:00:00Z",
                            "UTC",
                            "benchmark",
                            null,
                            text,
                        ),
                    )!!
                    db.execSQL(
                        "INSERT INTO chunk(capture_id, position, start_offset, end_offset, text) VALUES (?, 0, 0, ?, ?)",
                        arrayOf<Any>(id, text.length, text),
                    )
                    db.execSQL(
                        "INSERT INTO chunk_fts(rowid, text) SELECT id, text FROM chunk WHERE id = ?",
                        arrayOf<Any>(db.rawQuery("SELECT last_insert_rowid()", emptyArray<String>()).use { c ->
                            c.moveToFirst()
                            c.getLong(0)
                        }),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        session.withDatabase { HybridSearch(it).search("secure local knowledge", null) }
        val samples = LongArray(20) {
            val start = System.nanoTime()
            val hits = session.withDatabase { HybridSearch(it).search("secure local knowledge", null) }
            assertFalse(hits.isEmpty())
            (System.nanoTime() - start) / 1_000_000
        }.sorted()
        val p50 = samples[samples.lastIndex / 2]
        val p95 = samples[(samples.size * 0.95).roundToLong().toInt().coerceAtMost(samples.lastIndex)]
        Log.i(TAG, "notes=10000 samples=${samples.joinToString()} p50_ms=$p50 p95_ms=$p95 max_ms=${samples.last()}")
    }

    private companion object {
        const val TAG = "SEBRAIN_SCALE"
    }
}
