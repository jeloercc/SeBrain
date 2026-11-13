package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.Assistant
import com.sebrain.app.chat.ContextNote
import com.sebrain.app.chat.NoteFormat
import com.sebrain.app.chat.PastMessage
import com.sebrain.core.ml.ChatEngine
import com.sebrain.core.ml.ModelFiles
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 20-conversation evaluation of `docs/plan-memory.md`, step 6, with the model installed on
 * the phone. The vault is simulated: each case brings its own notes, pinned notes, past
 * messages or summary, and the app's own rules decide what reaches the model.
 *
 * Every turn has checks a program can make (a word that must appear, the language, the number
 * of notes). Results go to logcat under SEBRAIN_EVAL as PASS or FAIL lines and a final count.
 * The test itself only fails if the model cannot run. Run it with `am instrument`.
 */
@RunWith(AndroidJUnit4::class)
class AssistantEvalTest {
    private class Check(val name: String, val passes: (reply: String, notes: List<com.sebrain.app.chat.DraftNote>, linked: List<String>) -> Boolean)

    private class Turn(val message: String, val checks: List<Check>)

    private class Case(
        val name: String,
        val turns: List<Turn>,
        val notes: List<ContextNote> = emptyList(),
        val pinned: List<String> = emptyList(),
        val past: List<PastMessage> = emptyList(),
        val summary: String? = null,
        /** Messages before the first turn, for cases that start in the middle of a conversation. */
        val before: List<Assistant.Line> = emptyList(),
    )

    private lateinit var chat: ChatEngine

    @Before
    fun loadModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        chat = ChatEngine(context, ModelFiles(File(context.filesDir, "models")))
        val models = chat.languageModels()
        assumeTrue("no language model is installed", models.isNotEmpty())
        val saved = context.getSharedPreferences("sebrain", 0).getString("language_model", null)
        chat.load(models.firstOrNull { it.fileName == saved } ?: models.first())
    }

    @After
    fun release() = chat.close()

    // ---- Checks ----
    private fun has(vararg words: String) = Check("has ${words.joinToString("|")}") { reply, _, _ ->
        words.any { reply.contains(it, ignoreCase = true) }
    }
    private fun lacks(vararg words: String) = Check("lacks ${words.joinToString("|")}") { reply, _, _ ->
        words.none { reply.contains(it, ignoreCase = true) }
    }
    private fun lacksPattern(name: String, pattern: String) = Check("lacks $name") { reply, _, _ -> !Regex(pattern).containsMatchIn(reply) }
    private val spanish = Check("in Spanish") { reply, _, _ -> count(reply, SPANISH) > count(reply, ENGLISH) }
    // A short reply such as "SSH uses port 22 by default." has no common English word; it still
    // counts as English when nothing in it is Spanish.
    private val english = Check("in English") { reply, _, _ ->
        count(reply, ENGLISH) > count(reply, SPANISH) || (count(reply, SPANISH) == 0 && reply.none { it in "¿¡ñáéíóú" })
    }
    private val cites = Check("cites [1]") { reply, _, _ -> reply.contains("[1]") }
    private val citesNothing = lacksPattern("citation", "\\[\\d+]")
    private fun shorterThan(chars: Int) = Check("under $chars chars") { reply, _, _ -> reply.trim().length < chars }
    private fun noteCount(n: Int) = Check("$n note(s)") { _, notes, _ -> notes.size == n }
    private val wellFormed = Check("note format") { _, notes, _ ->
        notes.isNotEmpty() && notes.all { it.title.length in 2..60 && it.tags.size in 2..4 && it.body.contains("## ") && it.body.contains("\n- ") }
    }
    /** The note is not one already written earlier in this conversation. */
    private val newTitle = Check("new title") { _, notes, _ ->
        notes.isNotEmpty() && notes.none { note -> titlesBefore.any { it.equals(note.title, ignoreCase = true) } }
    }

    /** Titles of the notes written before the turn being checked. */
    private var titlesBefore: List<String> = emptyList()
    private fun titled(vararg words: String) = Check("title has ${words.joinToString("|")}") { _, notes, _ ->
        notes.any { note -> words.any { note.title.contains(it, ignoreCase = true) } }
    }
    private fun linksTo(title: String) = Check("links to [[$title]]") { _, _, linked -> linked.any { it.contains("[[$title]]") } }

    private fun count(text: String, words: Regex) = words.findAll(text.lowercase()).count()

    private fun note(number: Int, title: String, text: String, tags: List<String> = emptyList(), linksTo: List<String> = emptyList(), via: String? = null) =
        ContextNote(number, number.toLong(), title, tags, linksTo, emptyList(), text, via)

    private fun line(fromUser: Boolean, text: String) = Assistant.Line(fromUser, text)

    // ---- The 20 conversations ----
    private val coffee = note(1, "Café", "Tomo el café sin azúcar porque el azúcar me da dolor de cabeza. Siempre por la mañana.", listOf("habitos"))
    private val serverSummary = "El usuario monta un servidor casero llamado atlas con Debian 12 y Docker. Servicios: Jellyfin, Nextcloud y " +
        "Pi-hole. El disco de datos de 4 TB está en /srv/datos. Queda pendiente decidir las copias de seguridad."

    private val cases = listOf(
        Case(
            "01 next one, Spanish",
            listOf(
                Turn("Lista los tipos de kernel de sistemas operativos, solo los nombres", listOf(spanish, has("monol"), has("micro"))),
                Turn("haz una nota del primero", listOf(noteCount(1), wellFormed, titled("monol"), spanish)),
                Turn("explicame y haz la nota del siguiente", listOf(noteCount(1), titled("micro"), newTitle, spanish)),
                Turn("haz la nota del siguiente", listOf(noteCount(1), newTitle, spanish)),
            ),
        ),
        Case(
            "02 next one, English",
            listOf(
                Turn("List the three states of matter taught in school, names only", listOf(english, has("solid"), has("liquid"), has("gas"))),
                Turn("make a note about the first one", listOf(noteCount(1), wellFormed, titled("solid"), english)),
                Turn("now make the note for the next one", listOf(noteCount(1), titled("liquid"), newTitle, english)),
                Turn("which one is still missing?", listOf(has("gas"), english)),
            ),
        ),
        Case(
            "03 short follow-up uses the note",
            listOf(
                Turn("¿cómo tomo el café?", listOf(has("sin azúcar", "sin azucar"), spanish, cites)),
                Turn("¿y por qué?", listOf(has("dolor de cabeza"), spanish)),
            ),
            notes = listOf(coffee),
        ),
        Case(
            "04 Spanish question, English note",
            listOf(Turn("¿qué dice mi nota sobre el backup?", listOf(spanish, has("domingo", "sunday"), has("restic")))),
            notes = listOf(note(1, "Backup plan", "Backups run every Sunday at night with restic to the external disk.")),
        ),
        Case(
            "05 English question, Spanish note",
            listOf(Turn("How do I take my coffee, according to my notes?", listOf(english, has("sugar")))),
            notes = listOf(coffee),
        ),
        Case(
            "06 no note, no citation",
            listOf(Turn("¿qué es una dirección IP?", listOf(spanish, citesNothing))),
        ),
        Case(
            "07 unrelated note is not used",
            listOf(Turn("¿cuál es la capital de Australia?", listOf(has("Canberra"), spanish, lacks("café", "azúcar")))),
            notes = listOf(coffee),
        ),
        Case(
            "08 does not invent what the note lacks",
            listOf(Turn("¿a qué hora es la reunión con el banco?", listOf(spanish, has("martes"), lacksPattern("a clock time", "\\b\\d{1,2}[:.]\\d{2}\\b|\\ba las \\d")))),
            notes = listOf(note(1, "Reunión banco", "La reunión con el banco es el martes. Llevar el contrato firmado.")),
        ),
        Case(
            "09 pinned instruction is obeyed",
            listOf(Turn("explica qué es DNS", listOf(spanish, shorterThan(420)))),
            pinned = listOf("Responde siempre en una sola frase corta."),
        ),
        Case(
            "10 pinned fact is recalled",
            listOf(Turn("¿cómo se llama mi servidor y qué sistema usa?", listOf(has("atlas"), has("Debian"), spanish))),
            pinned = listOf("Mi servidor casero se llama atlas y usa Debian 12."),
        ),
        Case(
            "11 past conversation is recalled",
            listOf(Turn("¿en qué puerto dejamos jellyfin?", listOf(has("8096"), spanish))),
            past = listOf(PastMessage("Servidor atlas", "2026-11-08T10:00:00Z", false, "Jellyfin queda escuchando en el puerto 8096 de atlas.")),
        ),
        Case(
            "12 summary is recalled",
            listOf(Turn("¿dónde está montado el disco de datos y qué quedó pendiente?", listOf(has("/srv/datos"), has("copia", "backup", "seguridad"), spanish))),
            summary = serverSummary,
            before = (1..7).flatMap { listOf(line(true, "mensaje antiguo $it"), line(false, "respuesta antigua $it")) },
        ),
        Case(
            "13 several notes in one request",
            listOf(Turn("crea una nota para cada una de estas herramientas: Git y Docker", listOf(noteCount(2), wellFormed, titled("Git"), titled("Docker"), spanish))),
        ),
        Case(
            "14 one note keeps the format",
            listOf(Turn("haz una nota sobre qué es una VPN", listOf(noteCount(1), wellFormed, titled("VPN"), spanish, lacks("¿Quieres", "¿Te gustaría")))),
        ),
        Case(
            "15 a new note links to an existing one",
            listOf(Turn("haz una nota sobre UDP comparándolo con TCP", listOf(noteCount(1), titled("UDP"), linksTo("TCP")))),
            notes = listOf(note(1, "TCP", "TCP es un protocolo de transporte orientado a la conexión, fiable y ordenado.", listOf("redes"))),
        ),
        Case(
            "16 uses the links between notes",
            listOf(Turn("¿qué tiene que ver Docker con mi proyecto?", listOf(has("atlas"), has("Docker"), spanish))),
            notes = listOf(
                note(1, "Proyecto Atlas", "Atlas es mi servidor casero. Todos los servicios corren en contenedores.", listOf("proyectos"), linksTo = listOf("Docker")),
                note(2, "Docker", "Docker ejecuta cada servicio aislado en un contenedor.", listOf("herramientas"), via = "linked from [1]"),
            ),
        ),
        Case(
            "17 code goes in a fenced block",
            listOf(Turn("dame un script bash que liste los archivos de una carpeta ordenados por tamaño", listOf(has("```"), has("ls", "du")))),
        ),
        Case(
            "18 follow-up refers to the previous answer",
            listOf(
                Turn("explica qué es un contenedor de software", listOf(spanish)),
                Turn("resúmelo en una sola frase", listOf(spanish, shorterThan(350), has("contenedor"))),
            ),
        ),
        Case(
            "19 a question is not saved as a note",
            listOf(Turn("¿qué dicen mis notas sobre el café?", listOf(noteCount(0), has("azúcar", "azucar"), spanish))),
            notes = listOf(coffee),
        ),
        Case(
            "20 language switch inside a conversation",
            listOf(
                Turn("¿qué es SSH?", listOf(spanish)),
                Turn("and what port does it use by default?", listOf(english, has("22"))),
            ),
        ),
    )

    @Test
    fun twentyConversations() {
        var passed = 0
        var total = 0
        val failed = mutableListOf<String>()
        // `-e cases 06,13` runs only those conversations, for example after a lost connection.
        val only = InstrumentationRegistry.getArguments().getString("cases")?.split(',')?.map { it.trim() }
        val selected = cases.filter { case -> only == null || only.any { case.name.startsWith(it) } }
        for (case in selected) {
            val earlier = case.before.toMutableList()
            val written = mutableListOf<ContextNote>()
            for ((index, turn) in case.turns.withIndex()) {
                val memory = Assistant.remember(earlier, case.summary, if (case.summary != null) case.before.size else 0)
                val asNote = Assistant.wantsNote(turn.message)
                val exclude = (if (asNote) memory.covered else memory.recent.filter { !it.fromUser }.mapNotNull { Assistant.heading(it.text) })
                    .map { it.lowercase() }
                // The notes of the case, then the notes written earlier in this conversation, as the vault would return them.
                val context = (case.notes + written).filter { it.title.lowercase() !in exclude }
                    .mapIndexed { i, n -> n.copy(number = i + 1) }
                val prompt = Assistant.prompt(turn.message, context, asNote, emptyList(), memory, case.pinned, case.past)
                val start = System.nanoTime()
                // As in the app: citations to notes that were not given are removed from the reply.
                val reply = runCatching {
                    Assistant.dropUnknownCitations(
                        chat.reply(Assistant.SYSTEM, memory.recent.map { ChatEngine.Turn(it.fromUser, it.text) }, prompt) {},
                        context.size,
                    )
                }.getOrElse { "[error: ${it.message}]" }
                val seconds = (System.nanoTime() - start) / 1e9
                val notes = if (asNote) NoteFormat.parse(reply, turn.message) else emptyList()
                val linked = notes.map { Assistant.linkify(it.body, context, it.title) }
                titlesBefore = written.map { it.title }
                val bad = turn.checks.filterNot { it.passes(reply, notes, linked) }.map { it.name }
                notes.forEach { written += ContextNote(0, 0, it.title, it.tags, emptyList(), emptyList(), it.body) }
                earlier += line(true, turn.message)
                earlier += line(false, reply)

                total += turn.checks.size
                passed += turn.checks.size - bad.size
                val label = "${case.name} / turn ${index + 1}"
                if (bad.isEmpty()) {
                    log("PASS $label (%.0f s)".format(seconds))
                } else {
                    failed += "$label: ${bad.joinToString(", ")}"
                    log("FAIL $label (%.0f s): ${bad.joinToString(", ")}".format(seconds))
                    log("     message=${turn.message}")
                    log("     reply=${reply.replace("\n", "⏎").take(700)}")
                }
            }
        }
        log("RESULT checks passed $passed of $total; turns failed ${failed.size} of ${selected.sumOf { it.turns.size }}")
        failed.forEach { log("FAILED $it") }
    }

    private fun log(line: String) {
        Log.i("SEBRAIN_EVAL", line)
    }

    private companion object {
        val SPANISH = Regex("\\b(el|la|los|las|de|que|es|un|una|en|por|para|con|se|del|y)\\b")
        val ENGLISH = Regex("\\b(the|is|of|and|to|a|in|it|for|that|with|are|on)\\b")
    }
}
