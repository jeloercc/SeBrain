package com.sebrain.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.crypto.WrapKey
import com.sebrain.core.crypto.WrappedDekStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var wrapKey: WrapKey
    private lateinit var session: VaultSession

    @Before
    fun setUp() {
        dir = File(context.noBackupFilesDir, "conversation-test-${System.nanoTime()}").apply { mkdirs() }
        wrapKey = WrapKey(alias = "test_conversation_${System.nanoTime()}")
        session = VaultSession(context, File(dir, "vault.db"), WrappedDekStore(File(dir, "dek.wrapped"), wrapKey))
    }

    @After
    fun tearDown() {
        session.close()
        wrapKey.delete()
        dir.deleteRecursively()
    }

    private fun conversation(title: String, vararg messages: Pair<Boolean, String>): Long = session.withDatabase { db ->
        val store = ConversationStore(db)
        val id = store.create(title, "2026-11-12T10:00:00Z")
        messages.forEach { (fromUser, text) -> store.add(id, fromUser, text, "2026-11-12T10:00:01Z") }
        id
    }

    @Test
    fun messagesAreStoredInOrderAndListedByConversation() {
        val id = conversation("Kernels", true to "List the kernel types", false to "Monolithic and microkernel.")
        session.withDatabase { db ->
            val store = ConversationStore(db)
            assertEquals(listOf(true to "List the kernel types", false to "Monolithic and microkernel."), store.messages(id).map { it.fromUser to it.text })
            assertEquals(listOf(ConversationSummary(id, "Kernels", "2026-11-12T10:00:01Z", 2)), store.list())
        }
    }

    @Test
    fun searchFindsMessagesInOtherConversationsAndIgnoresAccents() {
        val kernels = conversation("Kernels", true to "¿Qué es un núcleo monolítico?", false to "Un diseño con todo en el kernel.")
        val coffee = conversation("Coffee", true to "How do I brew coffee?", false to "Use water at 93 degrees.")
        session.withDatabase { db ->
            val store = ConversationStore(db)
            assertEquals(listOf(kernels), store.search("nucleo monolitico", 5).map { it.conversationId }.distinct())
            assertEquals("Kernels", store.search("monolitico", 5).single().title)
            // The open conversation is left out, and words under four letters do not search.
            assertTrue(store.search("monolitico", 5, exclude = kernels).isEmpty())
            assertTrue(store.search("un el de", 5).isEmpty())
            val found = store.searchConversations("coffee water")
            assertEquals(listOf(coffee), found.map { it.id })
            assertEquals(2, found.single().messages)
        }
    }

    @Test
    fun deletingAConversationRemovesItFromSearch() {
        val id = conversation("Kernels", true to "exokernel details")
        session.withDatabase { db ->
            val store = ConversationStore(db)
            store.delete(id)
            assertTrue(store.search("exokernel", 5).isEmpty())
            assertTrue(store.list().isEmpty())
            assertTrue(store.messages(id).isEmpty())
        }
    }

    @Test
    fun aQueryCannotBeReadAsSearchSyntax() {
        conversation("Kernels", true to "exokernel details")
        session.withDatabase { db ->
            assertEquals(1, ConversationStore(db).search("exokernel\" OR NOT (text:*", 5).size)
        }
    }

    @Test
    fun theSummaryRemembersHowManyMessagesItCovers() {
        val id = conversation("Kernels", true to "one", false to "two", true to "three")
        session.withDatabase { db ->
            val store = ConversationStore(db)
            assertEquals(null, store.summary(id))
            assertTrue(store.setSummary(id, "They counted.", covered = 2))
            assertEquals("They counted." to 2, store.summary(id))
            assertEquals(false, store.setSummary(id, "Too far.", covered = 4))
            assertEquals("They counted." to 2, store.summary(id))
        }
    }

    @Test
    fun aBackupCarriesConversationsIntoAnotherVault() {
        val id = conversation("Kernels", true to "List the kernel types", false to "Monolithic and exokernel.", true to "thanks")
        val snapshot = session.withDatabase { db ->
            ConversationStore(db).setSummary(id, "They listed kernels.", covered = 2)
            VaultBackupSnapshot.export(db)
        }

        val otherDir = File(context.noBackupFilesDir, "conversation-restore-${System.nanoTime()}").apply { mkdirs() }
        val otherKey = WrapKey(alias = "test_conversation_restore_${System.nanoTime()}")
        val other = VaultSession(context, File(otherDir, "vault.db"), WrappedDekStore(File(otherDir, "dek.wrapped"), otherKey))
        try {
            other.withDatabase { db ->
                assertEquals(0, VaultBackupSnapshot.restore(db, snapshot))
                val store = ConversationStore(db)
                val restored = store.list().single()
                assertEquals("Kernels", restored.title)
                assertEquals(3, restored.messages)
                assertEquals(
                    listOf(true to "List the kernel types", false to "Monolithic and exokernel.", true to "thanks"),
                    store.messages(restored.id).map { it.fromUser to it.text },
                )
                assertEquals("They listed kernels." to 2, store.summary(restored.id))
                // The restored messages are searchable.
                assertEquals(listOf(restored.id), store.search("exokernel", 5).map { it.conversationId })
            }
        } finally {
            other.close()
            otherKey.delete()
            otherDir.deleteRecursively()
        }
    }
}
