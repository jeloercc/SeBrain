package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.Assistant
import com.sebrain.app.chat.NoteToolSet
import com.sebrain.app.chat.VaultContext
import com.sebrain.cognition.NoteIndexer
import com.sebrain.core.ml.ChatEngine
import com.sebrain.core.ml.ModelFiles
import com.sebrain.core.ml.TextEmbedder
import com.sebrain.core.vault.NoteStore
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan phase 3, the decision: does the model call the right tool by itself? Each message is sent
 * with [NoteToolSet] in manual mode, on a small made-up vault, and the first tool it calls is
 * compared with the expected one (none for general questions and small talk). Logged under
 * SEBRAIN_EVAL with the time per message. Fails only if no model is installed.
 */
@RunWith(AndroidJUnit4::class)
class ToolCallEvalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var vault: TestVault
    private lateinit var chat: ChatEngine
    private lateinit var embedder: TextEmbedder

    /** [tool] is the tool expected first, or null for none. */
    private class Case(val message: String, val tool: String?)

    @After
    fun tearDown() {
        if (::chat.isInitialized) chat.close()
        if (::embedder.isInitialized) embedder.close()
        if (::vault.isInitialized) vault.deleteEverything()
    }

    @Test
    fun toolCalls() {
        val models = ModelFiles(File(context.filesDir, "models"))
        chat = ChatEngine(context, models)
        val installed = chat.languageModels()
        assumeTrue("no language model is installed", installed.isNotEmpty())
        val wanted = InstrumentationRegistry.getArguments().getString("model")
        val model = installed.firstOrNull { wanted != null && it.fileName.contains(wanted, ignoreCase = true) } ?: installed.first()
        embedder = TextEmbedder(context, models)
        vault = TestVault(context, "tools_${System.nanoTime()}")
        val indexer = NoteIndexer { docs -> if (embedder.isAvailable) embedder.embedTitledDocuments(docs) else null }
        vault.session.withDatabase { db ->
            NOTES.forEach { text ->
                val id = NoteStore(db).create(text, NoteIndexer.sha256Hex(text), "2026-11-15T10:00:00Z", "UTC")!!
                indexer.index(db, id, text)
            }
        }
        chat.load(model)
        log("eval tools model=${model.fileName} backend=${chat.backend}")

        var right = 0
        var answered = 0
        val ms = mutableListOf<Long>()
        CASES.forEachIndexed { i, case ->
            val start = System.nanoTime()
            val reply = runCatching {
                chat.replyWithTools(Assistant.SYSTEM, emptyList(), case.message, NoteToolSet(), Assistant.ANSWER_TOKENS) { name, args -> run(name, args) }
            }
            ms += (System.nanoTime() - start) / NANOS_PER_MS
            val steps = reply.getOrNull()?.steps.orEmpty()
            val first = steps.firstOrNull()?.name
            val ok = reply.isSuccess && first == case.tool
            if (ok) right++
            if (reply.getOrNull()?.text?.isNotBlank() == true) answered++
            val text = reply.getOrNull()?.text ?: "ERROR ${reply.exceptionOrNull()?.javaClass?.simpleName}: ${reply.exceptionOrNull()?.message?.take(100)}"
            log(
                "${if (ok) "PASS" else "FAIL"} tool ${i + 1} expected=${case.tool} called=${steps.joinToString { "${it.name}${it.arguments}" }.ifEmpty { "none" }} " +
                    "ms=${ms.last()} q=\"${case.message}\" reply=${text.replace("\n", " ").take(REPLY_LOGGED)}",
            )
        }
        val sorted = ms.sorted()
        log("eval tools model=${model.fileName} right=$right/${CASES.size} answered=$answered/${CASES.size} median=${sorted[sorted.size / 2]} ms max=${sorted.last()} ms")
    }

    /** What the app would do for each tool: read-only here, and a write is only drafted. */
    private fun run(name: String, args: Map<String, Any?>): String = when (name) {
        NoteToolSet.SEARCH -> vault.session.withDatabase { db ->
            val query = args["query"]?.toString().orEmpty()
            val vector = if (embedder.isAvailable) embedder.embedQuery(query) else null
            VaultContext.build(db, query, vector).notes.take(MAX_RESULTS)
                .joinToString("\n\n") { "[${it.number}] ${it.title}\n${it.text.take(NOTE_CHARS)}" }
                .ifEmpty { "No note matches." }
        }
        NoteToolSet.READ -> vault.session.withDatabase { db ->
            val title = args["title"]?.toString().orEmpty()
            val store = NoteStore(db)
            store.names().firstOrNull { it.third.equals(title, ignoreCase = true) }?.let { store.get(it.first)?.text } ?: "No note has that title."
        }
        NoteToolSet.CREATE -> "Drafted \"${args["title"]}\"; the user will review it before it is saved."
        else -> "Unknown tool."
    }

    private fun log(line: String) {
        Log.i("SEBRAIN_EVAL", line)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MAX_RESULTS = 4
        const val NOTE_CHARS = 600
        const val REPLY_LOGGED = 160
        val NOTES = listOf(
            "# Dentista\n\nMi dentista es la Dra. Okafor, en la consulta de la Calle Lirio 14. Próxima revisión: jueves 12 a las 17:15.\n\n#salud\n",
            "# Car service\n\nThe Corolla had its last oil change at 84,200 km. The next one is due at 94,000 km.\n\n#car\n",
            "# Trip to Lisbon\n\nFlight TP1023 on 14 March. Hotel near Alfama, booking code KX7Q2.\n\n#travel\n",
            "# Project Heron\n\nHeron is the billing migration at work. Deadline: 30 January. Contact: Priya in finance.\n\n#work\n",
            "# Gym\n\nLocker 204 at the north gym. Combination 9-2-6.\n\n#deporte\n",
            "# Lentejas de la abuela\n\nLlevan comino, una hoja de laurel y un chorizo.\n\n#recetas\n",
        )
        val CASES = listOf(
            Case("¿Cuándo es mi próxima cita con el dentista?", NoteToolSet.SEARCH),
            Case("When is the next oil change for my car?", NoteToolSet.SEARCH),
            Case("What's the booking code for my Lisbon hotel?", NoteToolSet.SEARCH),
            Case("¿Quién es mi contacto en Heron?", NoteToolSet.SEARCH),
            Case("what's my locker combination", NoteToolSet.SEARCH),
            Case("¿qué lleva la receta de lentejas de la abuela?", NoteToolSet.SEARCH),
            Case("Read my note Project Heron", NoteToolSet.READ),
            Case("Apunta que mañana tengo que llamar al banco", NoteToolSet.CREATE),
            Case("Remember that my bike lock code is 4417", NoteToolSet.CREATE),
            Case("Guarda una nota con ideas para el cumpleaños de Marta: un libro y flores", NoteToolSet.CREATE),
            Case("¿Quién fue el primer fotógrafo?", null),
            Case("What can you tell me about NASA?", null),
            Case("¿Cuál es la capital de Australia?", null),
            Case("Explain how a VPN works", null),
            Case("dame una receta rápida de pasta", null),
            Case("write a bash one-liner that counts lines in all .kt files", null),
            Case("hola, ¿cómo estás?", null),
            Case("thanks, that was helpful", null),
            Case("¿qué hora es en Tokio cuando en Madrid son las 9?", null),
            Case("tell me a fun fact", null),
        )
    }
}
