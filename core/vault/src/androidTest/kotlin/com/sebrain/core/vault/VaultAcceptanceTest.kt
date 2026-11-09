package com.sebrain.core.vault

import android.database.Cursor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance checks of docs/plan-phase1.md that need a device. Numbers match the plan. */
@RunWith(AndroidJUnit4::class)
class VaultAcceptanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        VaultNative.load()
        dir = File(context.cacheDir, "vault-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun open(file: File, key: ByteArray): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(file, key, null, null)

    private fun <T> SQLiteDatabase.queryOne(sql: String, read: (Cursor) -> T): T =
        rawQuery(sql, emptyArray<String>()).use { c ->
            assertTrue("no row for: $sql", c.moveToFirst())
            read(c)
        }

    @Test
    fun check01_opensWithRawKeyAndReopens() = RawTestKey().use { key ->
        val file = File(dir, "a.db")
        open(file, key.sqlcipherKey).use { db ->
            db.execSQL("CREATE TABLE t(v TEXT)")
            db.execSQL("INSERT INTO t VALUES ('hello')")
        }
        open(file, key.sqlcipherKey).use { db ->
            assertEquals("hello", db.queryOne("SELECT v FROM t") { it.getString(0) })
        }
    }

    @Test
    fun check02_wrongKeyFailsWithoutDeletingAndRawKeySkipsKdf() = RawTestKey().use { key ->
        val file = File(dir, "a.db")
        open(file, key.sqlcipherKey).use { it.execSQL("CREATE TABLE t(v)") }

        RawTestKey().use { wrong ->
            assertThrows(SQLiteNotADatabaseException::class.java) {
                open(file, wrong.sqlcipherKey).close()
            }
        }
        assertTrue("wrong key must not delete the file", file.exists())
        open(file, key.sqlcipherKey).use { db ->
            assertEquals(1L, db.queryOne("SELECT count(*) FROM sqlite_master") { it.getLong(0) })
        }

        val control = File(dir, "control.db")
        val passphrase = "control-passphrase-not-a-vault-key".toByteArray()
        open(control, passphrase).use { it.execSQL("CREATE TABLE t(v)") }
        val rawMs = timedOpenMs(file, key.sqlcipherKey)
        val kdfMs = timedOpenMs(control, passphrase)
        Log.i(TAG, "check02 openRawMs=$rawMs openPassphraseMs=$kdfMs")
        assertTrue("raw=$rawMs ms should be far below passphrase=$kdfMs ms", rawMs * 3 < kdfMs)
    }

    private fun timedOpenMs(file: File, key: ByteArray): Long {
        val start = System.nanoTime()
        open(file, key).close()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun check03_fileIsEncryptedOnDisk() = RawTestKey().use { key ->
        val file = File(dir, "a.db")
        val marker = "SEBRAIN_PLAINTEXT_MARKER_7f3a"
        open(file, key.sqlcipherKey).use { db ->
            db.execSQL("CREATE TABLE t(v TEXT)")
            db.execSQL("INSERT INTO t VALUES ('$marker')")
        }
        val bytes = file.readBytes()
        assertFalse(
            "file starts with the SQLite header",
            bytes.copyOf(16).contentEquals("SQLite format 3\u0000".toByteArray()),
        )
        assertFalse("plaintext marker found on disk", String(bytes, Charsets.ISO_8859_1).contains(marker))
    }

    @Test
    fun check04_versions() = RawTestKey().use { key ->
        open(File(dir, "a.db"), key.sqlcipherKey).use { db ->
            val cipher = db.queryOne("PRAGMA cipher_version") { it.getString(0) }
            val sqlite = db.queryOne("SELECT sqlite_version()") { it.getString(0) }
            val provider = db.queryOne("PRAGMA cipher_provider") { it.getString(0) }
            Log.i(TAG, "check04 cipher=$cipher sqlite=$sqlite provider=$provider")
            assertTrue(cipher, cipher.startsWith("4.20.0"))
            assertEquals("3.54.1", sqlite)
            assertEquals("libtomcrypt", provider)
        }
    }

    @Test
    fun check05_vecVersionOnEveryConnection() = RawTestKey().use { key ->
        val first = File(dir, "a.db")
        val second = File(dir, "b.db")
        open(first, key.sqlcipherKey).use { a ->
            open(second, key.sqlcipherKey).use { b ->
                assertEquals("v0.1.9", a.queryOne("SELECT vec_version()") { it.getString(0) })
                assertEquals("v0.1.9", b.queryOne("SELECT vec_version()") { it.getString(0) })
            }
        }
    }

    @Test
    fun check06_vec0KnnMatchesBruteForce() = RawTestKey().use { key ->
        val vectors = randomVectors(VECTOR_COUNT, seed = 42)
        val query = randomVectors(1, seed = 7).getValue(1L)
        open(File(dir, "a.db"), key.sqlcipherKey).use { db ->
            insertVectors(db, vectors)
            assertEquals(bruteForceKnn(vectors, query), knn(db, query))
        }
    }

    @Test
    fun check07_vec0DeleteAndPersistence() = RawTestKey().use { key ->
        val vectors = randomVectors(VECTOR_COUNT, seed = 42)
        val query = randomVectors(1, seed = 7).getValue(1L)
        val file = File(dir, "a.db")
        val removed = bruteForceKnn(vectors, query).first()
        open(file, key.sqlcipherKey).use { db ->
            insertVectors(db, vectors)
            db.execSQL("DELETE FROM v WHERE rowid = $removed")
            assertFalse(knn(db, query).contains(removed))
        }
        open(file, key.sqlcipherKey).use { db ->
            assertEquals(bruteForceKnn(vectors - removed, query), knn(db, query))
        }
    }

    @Test
    fun check08_fts5() = RawTestKey().use { key ->
        open(File(dir, "a.db"), key.sqlcipherKey).use { db ->
            val options = db.rawQuery("PRAGMA compile_options", emptyArray<String>()).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
            }
            assertTrue(options.toString(), options.contains("ENABLE_FTS5"))

            db.execSQL("CREATE VIRTUAL TABLE d USING fts5(body, tokenize='unicode61 remove_diacritics 2')")
            db.execSQL("INSERT INTO d(rowid, body) VALUES (1, 'reunión reunión equipo')")
            db.execSQL(
                "INSERT INTO d(rowid, body) VALUES " +
                    "(2, 'una reunión con el equipo de diseño para hablar de muchas otras cosas largas')",
            )
            db.execSQL("INSERT INTO d(rowid, body) VALUES (3, 'factura pendiente')")
            val ids = db.rawQuery(
                "SELECT rowid FROM d WHERE d MATCH 'reunion' ORDER BY bm25(d)",
                emptyArray<String>(),
            ).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }
            assertEquals(listOf(1L, 2L), ids)
        }
    }

    @Test
    fun check09_loadExtensionIsOff() = RawTestKey().use { key ->
        open(File(dir, "a.db"), key.sqlcipherKey).use { db ->
            val error = assertThrows(Exception::class.java) {
                db.queryOne("SELECT load_extension('libz.so')") { it.getString(0) }
            }
            assertTrue(error.message, error.message.orEmpty().contains("no such function"))
        }
    }

    /** Passes only on a device started with `-e expectedPageSize 16384`. */
    @Test
    fun check12_pageSizeMatchesExpected() {
        val expected = InstrumentationRegistry.getArguments().getString("expectedPageSize")
        assumeTrue("expectedPageSize not set", expected != null)
        assertEquals(expected!!.toLong(), Os.sysconf(OsConstants._SC_PAGESIZE))
    }

    @Test
    fun keyMaterialIsZeroedAfterClose() {
        val key = RawTestKey()
        assertFalse(key.isZeroed)
        assertEquals("x", String(key.sqlcipherKey, 0, 1, Charsets.US_ASCII))
        assertEquals(67, key.sqlcipherKey.size)
        val file = File(dir, "a.db")
        key.use { k ->
            open(file, k.sqlcipherKey).use { db ->
                db.execSQL("CREATE TABLE t(v)")
                assertNotNull(db.queryOne("SELECT count(*) FROM t") { it.getLong(0) })
            }
        }
        assertTrue("key material must be zero", key.isZeroed)
        assertArrayEquals(ByteArray(67), key.sqlcipherKey)
    }

    private fun insertVectors(db: SQLiteDatabase, vectors: Map<Long, FloatArray>) {
        db.execSQL("CREATE VIRTUAL TABLE v USING vec0(embedding float[$DIMENSIONS])")
        db.beginTransaction()
        try {
            db.compileStatement("INSERT INTO v(rowid, embedding) VALUES (?, ?)").use { st ->
                for ((id, vector) in vectors) {
                    st.clearBindings()
                    st.bindLong(1, id)
                    st.bindBlob(2, toBlob(vector))
                    st.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun knn(db: SQLiteDatabase, query: FloatArray): List<Long> =
        db.rawQuery(
            "SELECT rowid FROM v WHERE embedding MATCH ? AND k = $K ORDER BY distance",
            toBlob(query) as Any,
        ).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }

    private fun bruteForceKnn(vectors: Map<Long, FloatArray>, query: FloatArray): List<Long> =
        vectors.entries.sortedBy { (_, v) -> squaredDistance(v, query) }.take(K).map { it.key }

    private fun squaredDistance(a: FloatArray, b: FloatArray): Double =
        a.indices.sumOf { i -> ((a[i] - b[i]) * (a[i] - b[i])).toDouble() }

    private fun randomVectors(count: Int, seed: Long): Map<Long, FloatArray> {
        val random = Random(seed)
        return (1L..count.toLong()).associateWith {
            val v = FloatArray(DIMENSIONS) { random.nextGaussian().toFloat() }
            val norm = kotlin.math.sqrt(v.sumOf { x -> (x * x).toDouble() }).toFloat()
            FloatArray(DIMENSIONS) { i -> v[i] / norm }
        }
    }

    private fun toBlob(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            vector.forEach { putFloat(it) }
        }.array()

    private companion object {
        const val TAG = "SEBRAIN_VAULT"
        const val DIMENSIONS = 256
        const val VECTOR_COUNT = 1000
        const val K = 8
    }
}
