package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.Assistant
import com.sebrain.app.chat.NoteFormat
import com.sebrain.app.chat.Templates
import com.sebrain.app.chat.VaultContext
import com.sebrain.cognition.FrontMatter
import com.sebrain.cognition.NoteIndexer
import com.sebrain.core.ml.ChatEngine
import com.sebrain.core.ml.ModelFiles
import com.sebrain.core.vault.NoteStore
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The agent's long-term memory on Obsidian's model, end to end: recall by time (a note written
 * yesterday, yesterday's daily note), by property (every `status: pending` note, with the
 * total), and a note written from the user's template. What the app gives the model is
 * asserted; what the model writes is logged, except the template's properties, which the app
 * adds and are asserted. Throwaway vault with its own keys; synthetic content.
 *
 * Opt-in: `-e eval memory`.
 */
@RunWith(AndroidJUnit4::class)
class MemoryScenarioTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as SeBrainApp
    private val vault = TestVault(context, "memory_${System.nanoTime()}")
    private val indexer = NoteIndexer { docs -> if (app.embedder.isAvailable) app.embedder.embedTitledDocuments(docs) else null }
    private val tools = NoteTools(vault.session, indexer) {}
    private val now = LocalDateTime.now()
    private val today = now.toLocalDate()
    private lateinit var chat: ChatEngine

    @After
    fun tearDown() {
        if (::chat.isInitialized) chat.close()
        vault.deleteEverything()
    }

    private fun note(text: String, day: LocalDate, path: String? = null) {
        vault.session.withDatabase { db ->
            val store = NoteStore(db)
            val id = store.create(text, NoteStore.contentHash(text, path), "${day}T10:00:00Z", "UTC")!!
            if (path != null) store.setPath(id, path)
            indexer.index(db, id, text)
        }
    }

    private fun context(message: String): VaultContext.Result {
        val vector = runCatching { app.embedder.embedQuery(message) }.getOrNull()
        return vault.session.withDatabase { db -> VaultContext.build(db, message, vector, message = message, today = today) }
    }

    private fun log(line: String) {
        Log.i("SEBRAIN_EVAL", "memory $line")
    }

    @Test
    fun theAgentRemembersByTimeByPropertyAndWritesFromTemplates() {
        assumeTrue("opt-in: pass -e eval memory", arguments.getString("eval") == "memory")
        val yesterday = today.minusDays(1)
        note("# Router setup\n\nThe router's admin page is at 192.168.1.1.", yesterday)
        note("# $yesterday\n\nCalled the bank about the card.", today, path = "Daily/$yesterday")
        note("# Old plan\n\nMove the server to the attic.", today.minusDays(40))
        for ((task, status) in listOf("Pay rent" to "pending", "Call Ana" to "pending", "Renew passport" to "pending", "Fix bike" to "done")) {
            note("---\nstatus: $status\n---\n# $task\n\nTo do: $task.", today.minusDays(3))
        }
        note(
            "---\ntype: meeting\nstatus: open\ndate: \"{{date}}\"\n---\n# {{title}}\n\n## Attendees\n\n## Decisions\n\n## Next steps",
            today.minusDays(10), path = "Templates/Meeting",
        )

        // 1. By time: what was written yesterday, and yesterday's daily note; not the old plan.
        val byTime = context("What did I write yesterday?").notes.filter { it.section != null }
        log("time section=${byTime.firstOrNull()?.section} titles=${byTime.map { it.title }}")
        assertEquals(setOf("Router setup", yesterday.toString()), byTime.map { it.title }.toSet())

        // 2. By property: every pending task, with the exact total, and not the done one.
        val pending = context("Which tasks have status pending?").notes.filter { it.section != null }
        log("property section=${pending.firstOrNull()?.section} titles=${pending.map { it.title }}")
        assertEquals(setOf("Pay rent", "Call Ana", "Renew passport"), pending.map { it.title }.toSet())
        assertTrue(pending.first().section!!.contains("status: pending (3 in total)"))
        assertTrue(pending.all { "status: pending" in it.properties })

        // 3. Templates: the request names one; the template itself is never recalled as memory.
        val meeting = context("Make a meeting note about the budget review")
        log("template chosen=${meeting.template?.title}")
        assertEquals("Meeting", meeting.template?.title)
        assertFalse(meeting.notes.any { it.title == "{{title}}" || it.title == "Meeting" })

        // 4. The model: answers logged; the template note's properties asserted.
        chat = ChatEngine(context, ModelFiles(File(context.filesDir, "models")))
        val models = chat.languageModels()
        assumeTrue("no language model installed", models.isNotEmpty())
        chat.load(models.firstOrNull { it.fileName.contains(arguments.getString("model") ?: "E4B", ignoreCase = true) } ?: models.first())
        for (question in listOf("What did I write yesterday?", "How many tasks have status pending, and which?")) {
            val built = context(question)
            val reply = chat.reply(Assistant.SYSTEM, emptyList(), Assistant.prompt(question, built.notes, false, built.tags, today = today), Assistant.ANSWER_TOKENS) {}
            log("answer question=\"$question\" reply=${reply.replace("\n", " ⏎ ").take(400)}")
        }
        val template = meeting.template!!
        val request = "Make a meeting note about the budget review"
        val prompt = Assistant.prompt(request, meeting.notes, true, meeting.tags, today = today, template = Templates.structure(template, now))
        val draft = NoteFormat.parse(chat.reply(Assistant.SYSTEM, emptyList(), prompt, Assistant.NOTE_TOKENS) {}, request).first()
        tools.createNoteFrom(draft.title, draft.body, draft.tags, "assistant", aliases = draft.aliases, templateProperties = Templates.properties(template, draft.title, now))
        val saved = vault.session.withDatabase { db -> NoteStore(db).let { s -> s.findByTitle(draft.title)?.let(s::get) } }!!
        val properties = FrontMatter.parse(saved.text)
        log("template note title=\"${draft.title}\" sections=${Regex("(?m)^## (.+)$").findAll(saved.text).map { it.groupValues[1] }.toList()} properties=${properties.properties.map { "${it.key}=${it.values}" }}")
        assertEquals(listOf("meeting"), properties.valuesOf("type"))
        assertEquals(listOf("open"), properties.valuesOf("status"))
        assertEquals(listOf(today.toString()), properties.valuesOf("date"))
        log("RESULT ok")
    }
}
