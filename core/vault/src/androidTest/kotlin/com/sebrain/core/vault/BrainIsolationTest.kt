package com.sebrain.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Sub-brains are top-level folders, and each sees only its own notes, links and chats. */
@RunWith(AndroidJUnit4::class)
class BrainIsolationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var wrapKey: WrapKey
    private lateinit var session: VaultSession
    private val now = "2026-11-15T10:00:00Z"

    @Before
    fun setUp() {
        dir = File(context.noBackupFilesDir, "brains-test-${System.nanoTime()}").apply { mkdirs() }
        wrapKey = WrapKey(alias = "test_brains_${System.nanoTime()}")
        session = VaultSession(context, File(dir, "vault.db"), WrappedDekStore(File(dir, "dek.wrapped"), wrapKey))
    }

    @After
    fun tearDown() {
        session.close()
        wrapKey.delete()
        dir.deleteRecursively()
    }

    private fun note(title: String, path: String?, text: String = "Texto de $title", tags: List<String> = emptyList(), links: List<String> = emptyList()): Long =
        session.withDatabase { db ->
            val store = NoteStore(db)
            val id = store.create(text, NoteStore.contentHash(text, null), now, "UTC")!!
            if (path != null) assertTrue(store.setPath(id, path))
            store.setMarkup(id, title, tags, links.map { NoteLink(it) })
            ChunkStore(db).index(id, listOf(Chunk(0, 0, text.length, text)), null)
            id
        }

    @Test
    fun eachBrainSeesOnlyItsNotesAndTheUniversalBrainSeesAll() {
        val tcp = note("TCP", "Redes/TCP", tags = listOf("redes"))
        val udp = note("UDP", "Redes/Protocolos/UDP", tags = listOf("redes"), links = listOf("TCP"))
        val diary = note("Diario", "Personal/Diario", tags = listOf("vida"), links = listOf("TCP"))
        val loose = note("Suelta", null)
        session.withDatabase { db ->
            val redes = NoteStore(db, "Redes")
            assertEquals(setOf(tcp, udp), redes.recent(10).map { it.id }.toSet())
            assertEquals(2L, redes.count())
            assertEquals(listOf("redes" to 2), redes.tags())
            assertEquals(setOf("TCP", "UDP"), redes.names().map { it.third }.toSet())
            assertNull(redes.findByTitle("Diario"))
            // Backlinks stay inside: the Personal note that links to TCP is not seen from Redes.
            assertEquals(listOf(udp), redes.backlinks(tcp).map { it.id })
            assertEquals(setOf(tcp, udp), redes.graph().nodes.mapNotNull { it.id }.toSet())

            val personal = NoteStore(db, "personal")
            assertEquals(listOf(diary), personal.recent(10).map { it.id })
            // A link to a note of another brain is a concept not written in this brain.
            assertNull(personal.findByTitle("TCP"))
            assertEquals(listOf("TCP" to 1), personal.unresolvedLinks())
            val graph = personal.graph()
            assertTrue(graph.nodes.any { it.id == null && it.title == "TCP" })

            val universal = NoteStore(db)
            assertEquals(4L, universal.count())
            assertEquals(setOf(udp, diary), universal.backlinks(tcp).map { it.id }.toSet())
            assertTrue(loose in universal.recent(10).map { it.id })
            // "Redes" does not take in a folder that only starts the same way.
            note("Otra", "RedesSociales/X")
            assertEquals(2L, NoteStore(db, "Redes").count())
        }
    }

    @Test
    fun searchStaysInsideTheBrain() {
        note("TCP", "Redes/TCP", text = "protocolo fiable de transporte")
        note("Secreto", "Personal/Secreto", text = "protocolo personal de la familia")
        session.withDatabase { db ->
            assertEquals(2, HybridSearch(db).search("protocolo", null).size)
            val hits = HybridSearch(db, "Personal").search("protocolo", null)
            assertEquals(listOf("protocolo personal de la familia"), hits.map { it.text })
        }
    }

    @Test
    fun aBackupKeepsWhichBrainsArePrivateAndAnOldOneMakesThemAllPrivate() {
        note("TCP", "Redes/TCP")
        note("Diario", "Personal/Diario")
        val snapshot = session.withDatabase { db ->
            val registry = BrainRegistry(db)
            registry.list(now)
            registry.setPrivate(registry.find("Personal")!!.id, true, now)
            VaultBackupSnapshot.export(db)
        }

        restoreInto(snapshot) { db, restored ->
            assertEquals(0, restored.brainsMadePrivate)
            assertEquals(2, restored.brains)
            // Only the brain the user made private is private again.
            assertEquals(setOf("personal"), BrainRegistry(db).privateNames())
        }

        // A backup from before version 4 did not save the settings: every brain comes back private.
        val v3 = VaultBackupSnapshot.encode(VaultBackupSnapshot.decode(snapshot).records, version = 3)
        restoreInto(v3) { db, restored ->
            assertEquals(2, restored.brainsMadePrivate)
            assertEquals(setOf("redes", "personal"), BrainRegistry(db).privateNames())
        }
    }

    /** Restores [snapshot] into a new vault with its own key, runs [check], and deletes it. */
    private fun restoreInto(snapshot: ByteArray, check: (net.zetetic.database.sqlcipher.SQLiteDatabase, VaultBackupSnapshot.Restored) -> Unit) {
        val otherDir = File(context.noBackupFilesDir, "brains-restore-${System.nanoTime()}").apply { mkdirs() }
        val otherKey = WrapKey(alias = "test_brains_restore_${System.nanoTime()}")
        val other = VaultSession(context, File(otherDir, "vault.db"), WrappedDekStore(File(otherDir, "dek.wrapped"), otherKey))
        try {
            other.withDatabase { db -> check(db, VaultBackupSnapshot.restore(db, snapshot, now)) }
        } finally {
            other.close()
            otherKey.delete()
            otherDir.deleteRecursively()
        }
    }

    @Test
    fun notesMentioningCountsWholeWordsInsideTheBrain() {
        note("Shell", "Linux/Shell", text = "the shell reads commands")
        note("Cron", "Linux/Cron", text = "cron runs a shell script at night")
        note("Kernel", "Linux/Kernel", text = "the kernel schedules processes")
        note("Diario", "Personal/Diario", text = "a shell on the beach")
        session.withDatabase { db ->
            val linux = NoteStore(db, "Linux")
            assertEquals(2, linux.notesMentioning("shell"))
            assertEquals(1, linux.notesMentioning("shell script"))
            // Whole words only.
            assertEquals(0, linux.notesMentioning("she"))
            // FTS5 syntax in the input is only words: this is the phrase "shell OR kernel".
            assertEquals(0, linux.notesMentioning("shell\" OR \"kernel"))
            assertEquals(0, linux.notesMentioning("  -- "))
            assertEquals(3, NoteStore(db).notesMentioning("shell"))
        }
    }

    @Test
    fun brainsAreListedFromFoldersAndRememberWhetherTheyArePrivate() {
        note("TCP", "Redes/TCP")
        session.withDatabase { db ->
            val registry = BrainRegistry(db)
            val secretos = registry.create("Secretos", private = true, now = now)
            val brains = registry.list(now)
            assertEquals(listOf("Redes", "Secretos"), brains.map { it.name })
            assertEquals(1, brains.first { it.name == "Redes" }.notes)
            assertFalse(brains.first { it.name == "Redes" }.private)
            assertEquals(setOf("secretos"), registry.privateNames())
            registry.setPrivate(secretos.id, false, now)
            assertTrue(registry.privateNames().isEmpty())
            assertEquals(secretos.id, registry.create("secretos", private = true, now = now).id)
        }
    }

    @Test
    fun aNoteMovesBetweenBrainsWithAFreePath() {
        val id = note("TCP", null)
        note("Existing", "Redes/TCP")
        session.withDatabase { db ->
            val store = NoteStore(db)
            val path = store.freePath("Redes", "TCP")
            assertEquals("Redes/TCP (2)", path)
            assertTrue(store.setPath(id, path))
            assertEquals(2L, NoteStore(db, "Redes").count())
            assertTrue(store.setPath(id, null))
            assertEquals(1L, NoteStore(db, "Redes").count())
        }
    }

    @Test
    fun conversationsBelongToTheirBrain() {
        session.withDatabase { db ->
            val brain = BrainRegistry(db).create("Redes", private = false, now = now)
            val inBrain = ConversationStore(db, brain.id).create("TCP chat", now)
            ConversationStore(db, brain.id).add(inBrain, true, "explica el handshake", now)
            val universal = ConversationStore(db).create("General", now)
            ConversationStore(db).add(universal, true, "explica el handshake también", now)
            assertEquals(listOf(inBrain), ConversationStore(db, brain.id).list().map { it.id })
            assertEquals(setOf(inBrain, universal), ConversationStore(db).list().map { it.id }.toSet())
            assertEquals(listOf(inBrain), ConversationStore(db, brain.id).search("handshake", 5).map { it.conversationId })
        }
    }
}
