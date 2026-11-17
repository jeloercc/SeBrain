package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.Assistant
import com.sebrain.app.chat.NoteEdits
import com.sebrain.app.chat.NoteFormat
import com.sebrain.app.chat.VaultContext
import com.sebrain.cognition.FrontMatter
import com.sebrain.cognition.NoteIndexer
import com.sebrain.cognition.NoteMarkup
import com.sebrain.core.ml.ChatEngine
import com.sebrain.core.ml.ModelFiles
import com.sebrain.core.vault.BrainRegistry
import com.sebrain.core.vault.NoteStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An end-to-end run of the app's knowledge features on Linux content, in English, on a test
 * vault with its own keys: the user's vault is never opened. The phone's model writes the
 * notes, the real embedding model indexes them, and the assistant answers from them.
 *
 * Everything is logged under SEBRAIN_EVAL for a report. The test fails only if the model or the
 * vault cannot run, or if a brain reads a private brain's note. The content is synthetic.
 *
 * It takes minutes and depends on the model, so it is opt-in and skipped by
 * `connectedDebugAndroidTest`. Run it with:
 *
 * ```
 * adb shell am instrument -w -e scenario linux [-e model E4B] \
 *     -e class com.sebrain.app.LinuxScenarioTest com.sebrain.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LinuxScenarioTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as SeBrainApp
    private val vault = TestVault(context, "linux_${System.nanoTime()}")
    private val indexer = NoteIndexer { docs -> if (app.embedder.isAvailable) app.embedder.embedTitledDocuments(docs) else null }
    private val tools = NoteTools(vault.session, indexer) {}
    private lateinit var chat: ChatEngine

    @After
    fun tearDown() {
        if (::chat.isInitialized) chat.close()
        vault.deleteEverything()
    }

    @Test
    fun linuxKnowledgeBase() {
        assumeTrue("opt-in: pass -e $SCENARIO_ARGUMENT $SCENARIO", arguments.getString(SCENARIO_ARGUMENT) == SCENARIO)
        loadModel()
        createBrains()
        writeNotes()
        linkNotes()
        measureRetrieval()
        chatInBrain()
        checkIsolation()
        suggestTags()
    }

    /** Loads the model named by `-e model`, else an E4B model, else the first one installed. */
    private fun loadModel() {
        chat = ChatEngine(context, ModelFiles(File(context.filesDir, "models")), benchmark = true)
        val available = chat.languageModels()
        assumeTrue("no language model installed", available.isNotEmpty())
        val wanted = arguments.getString("model") ?: PREFERRED_MODEL
        val chosen = available.firstOrNull { it.fileName.contains(wanted, ignoreCase = true) } ?: available.first()
        val (_, load) = timed { chat.load(chosen); "" }
        log("setup model=${chosen.fileName} backend=${chat.backend} load=%.0f s embeddings=${app.embedder.isAvailable}".format(load))
    }

    /** 1. Two brains: Linux, and a private Personal brain to check isolation. */
    private fun createBrains() {
        vault.session.withDatabase { db ->
            BrainRegistry(db).create("Linux", private = false, now = NOW)
            BrainRegistry(db).create("Personal", private = true, now = NOW)
        }
        val personal = tools.createNoteFrom("Home server", PERSONAL_NOTE, listOf("personal"), "user", "Personal")
        // Without this note, the isolation check would pass for the wrong reason.
        assertTrue(personal, personal.startsWith("Created"))
    }

    /** 2. The phone's model writes the Linux notes, several per request, into the Linux brain. */
    private fun writeNotes() {
        val written = ArrayList<String>()
        var formatted = 0
        var total = 0
        for (request in NOTE_REQUESTS) {
            val (reply, seconds) = timed {
                val built = vault.session.withDatabase { db -> VaultContext.build(db, request, null, message = request, brain = "Linux") }
                val prompt = Assistant.prompt(request, built.notes, true, built.tags)
                chat.reply(Assistant.SYSTEM, emptyList(), prompt, Assistant.NOTE_TOKENS) {}
            }
            val drafts = NoteFormat.parse(reply, request)
            for (draft in drafts) {
                total++
                val ok = draft.body.contains("## ") && draft.body.contains("\n- ") && draft.tags.size in 2..4
                if (ok) formatted++
                val outcome = tools.createNoteFrom(draft.title, draft.body, draft.tags, "assistant", "Linux")
                if (outcome.startsWith("Created")) written += draft.title
                log("note title=\"${draft.title}\" tags=${draft.tags} sections=${Regex("(?m)^## ").findAll(draft.body).count()} bullets=${Regex("(?m)^- ").findAll(draft.body).count()} chars=${draft.body.length} format=${if (ok) "ok" else "off"} outcome=${outcome.take(60)}")
            }
            log("write request=\"$request\" seconds=%.1f notes=${drafts.size} looped=${chat.lastLooped}".format(seconds))
        }
        log("write RESULT notes=${written.size} formatted=$formatted/$total titles=$written")
    }

    /** 3. Linking: mentions of one note in another become links, without the model. */
    private fun linkNotes() {
        var linksAdded = 0
        vault.session.withDatabase { db ->
            val store = NoteStore(db, "Linux")
            val names = store.names().groupBy({ it.second }, { it.third }).map { it.key to it.value }
            val index = NoteEdits.Names(names)
            var after = 0L
            while (true) {
                val page = store.page(after, 50)
                if (page.isEmpty()) break
                after = page.last().id
                for (note in page) {
                    val result = NoteEdits.linked(note.text, index.candidatesFor(FrontMatter.body(note.text)).filter { it.first != note.title }) ?: continue
                    val before = NoteMarkup.parse(note.text).links.size
                    if (rewrite(db, store, note.id, result.text)) linksAdded += NoteMarkup.parse(result.text).links.size - before
                }
            }
            val graph = store.graph()
            log("links added=$linksAdded graph nodes=${graph.nodes.count { it.id != null }} edges=${graph.edges.size} unresolved=${graph.nodes.count { it.id == null }}")
        }
    }

    /** Replaces a note's text and its index together, as [NoteIndexer.index] requires. */
    private fun rewrite(db: net.zetetic.database.sqlcipher.SQLiteDatabase, store: NoteStore, id: Long, text: String): Boolean {
        db.beginTransaction()
        try {
            val changed = store.updateText(id, text, NoteIndexer.sha256Hex(text), NOW)
            if (changed) indexer.index(db, id, text)
            db.setTransactionSuccessful()
            return changed
        } finally {
            db.endTransaction()
        }
    }

    /** 4. Retrieval: each question should find the note whose title has the keyword, in its brain. */
    private fun measureRetrieval() {
        val ranks = ArrayList<Int?>()
        vault.session.withDatabase { db ->
            for ((question, keyword) in GOLDEN) {
                val vector = runCatching { app.embedder.embedQuery(question) }.getOrNull()
                val notes = VaultContext.build(db, question, vector, message = question, brain = "Linux").notes
                val rank = notes.indexOfFirst { it.title.contains(keyword, ignoreCase = true) }.takeIf { it >= 0 }?.plus(1)
                ranks += rank
                log("retrieve question=\"$question\" expected=$keyword rank=${rank ?: "miss"} top=${notes.take(3).map { it.title }}")
            }
        }
        val answerable = ranks.size
        fun recall(k: Int) = ranks.count { it != null && it <= k }.toDouble() / answerable
        log("retrieve RESULT questions=$answerable recall@1=%.2f recall@3=%.2f recall@8=%.2f MRR=%.2f".format(recall(1), recall(3), recall(8), ranks.sumOf { if (it == null) 0.0 else 1.0 / it } / answerable))
    }

    /** 5. Chat in the Linux brain: answers grounded in the notes, a follow-up, a named note. */
    private fun chatInBrain() {
        val history = ArrayList<Assistant.Line>()
        for (message in CHAT_MESSAGES) {
            val (reply, read) = ask(message, "Linux", history)
            log("chat check cites=${Regex("\\[\\d+]").containsMatchIn(reply)} english=${Assistant.language(reply) == "English"} read=${read.size}")
            history += Assistant.Line(true, message)
            history += Assistant.Line(false, reply)
        }
    }

    /** 6. Isolation: the Linux brain must not see the private Personal note; the universal brain does. */
    private fun checkIsolation() {
        val (fromLinux, readLinux) = ask(ISOLATION_QUESTION, "Linux")
        val (fromUniversal, readUniversal) = ask(ISOLATION_QUESTION, null)
        log(
            "isolation linuxReadPersonal=${readLinux.contains("Home server")} linuxSaysAtlas=${fromLinux.contains("atlas", ignoreCase = true)} " +
                "universalReadPersonal=${readUniversal.contains("Home server")} universalSaysAtlas=${fromUniversal.contains("atlas", ignoreCase = true)}",
        )
        // Retrieval is deterministic here (the brain filter), so it is asserted; the reply text is not.
        assertFalse("the Linux brain read a note of the private Personal brain", readLinux.contains("Home server"))
    }

    /** 7. A note tool: suggest tags for one note. */
    private fun suggestTags() {
        val note = vault.session.withDatabase { db -> NoteStore(db, "Linux").recent(1).firstOrNull()?.id?.let(NoteStore(db)::get) } ?: return
        val body = FrontMatter.body(note.text)
        val (reply, seconds) = timed {
            chat.reply(NoteEdits.TAGS_SYSTEM, emptyList(), NoteEdits.prompt(NoteEdits.Kind.TAGS, body, tags = listOf("linux", "sysadmin")), NoteEdits.maxTokens(NoteEdits.Kind.TAGS, body.length)) {}
        }
        log("tool TAGS note=\"${note.title}\" seconds=%.1f reply=${reply.trim().take(120)} proposal=${NoteEdits.tagged(note.text, reply) != null}".format(seconds))
    }

    /** One assistant turn the way the app makes it (AppState.generate), in [brain]. */
    private fun ask(message: String, brain: String?, history: List<Assistant.Line> = emptyList()): Pair<String, List<String>> {
        val memory = Assistant.remember(history)
        val asNote = Assistant.wantsNote(message)
        val query = Assistant.retrievalQuery(message, history.lastOrNull { it.fromUser }?.text)
        val exclude = if (asNote) memory.covered else memory.recent.filter { !it.fromUser }.mapNotNull { Assistant.heading(it.text) }
        val vector = runCatching { app.embedder.embedQuery(query) }.getOrNull()
        val built = vault.session.withDatabase { db ->
            val brainId = brain?.let { BrainRegistry(db).find(it)?.id }
            VaultContext.build(db, query, vector, exclude, null, message, brain, brainId)
        }
        val prompt = Assistant.prompt(
            message, built.notes, asNote, built.tags, memory, built.pinned, built.past,
            previousAnswer = history.lastOrNull { !it.fromUser }?.text,
        )
        val (reply, seconds) = timed {
            chat.reply(Assistant.SYSTEM, memory.recent.map { ChatEngine.Turn(it.fromUser, it.text) }, prompt, if (asNote) Assistant.NOTE_TOKENS else Assistant.ANSWER_TOKENS) {}
        }
        val clean = Assistant.dropUnknownCitations(Assistant.dropPreamble(reply), built.notes.size)
        log("turn brain=${brain ?: "universal"} seconds=%.1f read=${built.notes.map { it.title }} looped=${chat.lastLooped}".format(seconds))
        log("turn message=$message")
        log("turn reply=${clean.replace("\n", " ⏎ ").take(700)}")
        return clean to built.notes.map { it.title }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private fun timed(block: () -> String): Pair<String, Double> {
        val start = System.nanoTime()
        val text = block()
        return text to (System.nanoTime() - start) / 1e9
    }

    private companion object {
        const val TAG = "SEBRAIN_EVAL"
        const val SCENARIO_ARGUMENT = "scenario"
        const val SCENARIO = "linux"
        const val PREFERRED_MODEL = "E4B"
        const val NOW = "2026-11-16T10:00:00Z"
        const val PERSONAL_NOTE = "My home server runs Debian 12 and is called atlas. The admin password hint is the blue notebook."
        const val ISOLATION_QUESTION = "What is my home server called?"

        val NOTE_REQUESTS = listOf(
            "Create a note for each of these Linux topics: the Linux kernel, the shell, and the filesystem hierarchy",
            "Create a note for each of these: file permissions and chmod, processes and signals, and systemd",
            "Create a note for each of these: package management with apt, SSH remote access, and cron jobs",
        )

        /** Each question and a keyword the title of the note that answers it should contain. */
        val GOLDEN = listOf(
            "What does the kernel do between hardware and programs?" to "kernel",
            "How do I make a script executable for its owner only?" to "permission",
            "Which command line interpreter reads what I type?" to "shell",
            "Where are configuration files kept, under /etc or /var?" to "filesystem",
            "How do I stop a process that ignores Ctrl+C?" to "process",
            "How do I make a service start at boot?" to "systemd",
            "How do I install and update software on Debian?" to "apt",
            "How do I log into another machine with a key instead of a password?" to "ssh",
            "How do I run a backup every night at 2 am?" to "cron",
        )

        val CHAT_MESSAGES = listOf(
            "How do file permissions work in Linux?",
            "and how do I change them for a group?",
            "What does my note about systemd say about enabling a service?",
        )
    }
}
