package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.Assistant
import com.sebrain.app.chat.ContextNote
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
 * Answers you can trust (plan phase 5): the whole ASK path on a made-up vault, with its own
 * keys and directory, never the user's notes. Each question is searched like ASK does
 * ([VaultContext] with embeddings when installed), the prompt is [Assistant.prompt], and the
 * reply comes from the installed model. Three scores, logged under SEBRAIN_EVAL:
 *
 * - found: the note that holds the answer is among the notes given to the model;
 * - answered and cited: the reply has the fact and cites that note's number;
 * - honest: for a question the notes do not cover, the reply says so and invents nothing.
 *
 * The test fails only if no model is installed. Arguments: `-e model <part of a file name>`,
 * `-e cases 1,5,…` (case numbers, to rerun some).
 */
@RunWith(AndroidJUnit4::class)
class TrustEvalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private lateinit var vault: TestVault
    private lateinit var chat: ChatEngine
    private lateinit var embedder: TextEmbedder

    /** A question whose answer is in [source]; [fact] is a pattern the reply must contain. */
    private class Covered(val question: String, val source: String, val fact: String)

    /** A question the notes do not answer; [invented] is a pattern that would mean a made-up answer. */
    private class Uncovered(val question: String, val invented: String? = null)

    @After
    fun tearDown() {
        if (::chat.isInitialized) chat.close()
        if (::embedder.isInitialized) embedder.close()
        if (::vault.isInitialized) vault.deleteEverything()
    }

    @Test
    fun trust() {
        val models = ModelFiles(File(context.filesDir, "models"))
        chat = ChatEngine(context, models)
        val installed = chat.languageModels()
        assumeTrue("no language model is installed", installed.isNotEmpty())
        val wanted = arguments.getString("model")
        val model = installed.firstOrNull { wanted != null && it.fileName.contains(wanted, ignoreCase = true) } ?: installed.first()
        embedder = TextEmbedder(context, models)
        vault = TestVault(context, "trust_${System.nanoTime()}")
        val indexer = NoteIndexer { docs -> if (embedder.isAvailable) embedder.embedTitledDocuments(docs) else null }
        vault.session.withDatabase { db ->
            NOTES.forEach { text ->
                val id = NoteStore(db).create(text, NoteIndexer.sha256Hex(text), "2026-11-15T10:00:00Z", "UTC")!!
                indexer.index(db, id, text)
            }
        }
        chat.load(model)
        log("eval trust model=${model.fileName} backend=${chat.backend} embeddings=${embedder.isAvailable} notes=${NOTES.size}")

        val only = arguments.getString("cases")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        var found = 0
        var answered = 0
        var cited = 0
        var honest = 0
        var covered = 0
        var uncovered = 0
        COVERED.forEachIndexed { i, case ->
            val number = i + 1
            if (only != null && number !in only) return@forEachIndexed
            covered++
            val notes = notesFor(case.question)
            val source = notes.firstOrNull { it.title == case.source }
            val reply = ask(case.question, notes)
            val hasFact = Regex(case.fact, RegexOption.IGNORE_CASE).containsMatchIn(reply)
            val citesSource = source != null && reply.contains("[${source.number}]")
            if (source != null) found++
            if (hasFact) answered++
            if (hasFact && citesSource) cited++
            val ok = source != null && hasFact && citesSource
            log(
                "${if (ok) "PASS" else "FAIL"} covered $number found=${source?.number ?: "no"}/${notes.size} fact=$hasFact cited=$citesSource " +
                    "q=\"${case.question}\" reply=${reply.oneLine()}",
            )
        }
        UNCOVERED.forEachIndexed { i, case ->
            val number = COVERED.size + i + 1
            if (only != null && number !in only) return@forEachIndexed
            uncovered++
            val notes = notesFor(case.question)
            val reply = ask(case.question, notes)
            val admits = Regex(ADMITS, RegexOption.IGNORE_CASE).containsMatchIn(reply)
            val invents = case.invented != null && Regex(case.invented, RegexOption.IGNORE_CASE).containsMatchIn(reply)
            val ok = admits && !invents
            if (ok) honest++
            log("${if (ok) "PASS" else "FAIL"} uncovered $number admits=$admits invents=$invents q=\"${case.question}\" reply=${reply.oneLine()}")
        }
        log("eval trust model=${model.fileName} found=$found/$covered answered=$answered/$covered cited=$cited/$covered honest=$honest/$uncovered")
    }

    private fun notesFor(question: String): List<ContextNote> = vault.session.withDatabase { db ->
        val vector = if (embedder.isAvailable) embedder.embedQuery(question) else null
        VaultContext.build(db, question, vector).notes
    }

    /** The reply ASK would show: same prompt, system line, token limit and citation clean-up. */
    private fun ask(question: String, notes: List<ContextNote>): String {
        val prompt = Assistant.prompt(question, notes, asNote = false, tags = emptyList())
        val reply = runCatching { chat.reply(Assistant.SYSTEM, emptyList(), prompt, Assistant.ANSWER_TOKENS) {} }
            .getOrElse { "ERROR ${it.message?.take(120)}" }
        return Assistant.dropUnknownCitations(reply, notes.size)
    }

    private fun String.oneLine() = replace("\n", " ").take(REPLY_LOGGED)

    private fun log(line: String) {
        Log.i("SEBRAIN_EVAL", line)
    }

    private companion object {
        const val REPLY_LOGGED = 220

        /** Ways a reply says the notes do not have the answer, in English and Spanish. */
        const val ADMITS =
            "(notes?|vault)\\b[^.]{0,40}\\b(do(es)?n['’]?t|do(es)? not|not|no)\\b|" +
                "\\b(not|no|nothing)\\b[^.]{0,40}\\b(notes?|vault)\\b|" +
                "(don['’]?t|do not|does not|doesn['’]?t) (have|know|see|specify|say|mention|include|list)|no information|" +
                "no (especifica|indica)|isn['’]?t (in|mentioned)|not mentioned|" +
                "notas?\\b[^.]{0,40}\\bno\\b|\\bno\\b[^.]{0,40}\\bnotas?\\b|no (tengo|aparece|menciona|dice|hay|encuentro)"

        val NOTES = listOf(
            "# Dentista\n\nMi dentista es la Dra. Okafor, en la consulta de la Calle Lirio 14. Revisión cada seis meses.\n\n#salud\n",
            "# Wifi de casa\n\nLa red se llama Pinar-5G. El router está detrás de la tele del salón.\n\n#casa\n",
            "# Car service\n\nThe Corolla had its last oil change at 84,200 km. The next one is due at 94,000 km, at the garage on Mill Road.\n\n#car\n",
            "# Plan de entrenamiento\n\nLunes y jueves: fuerza. Miércoles: 8 km de carrera suave. El domingo es de descanso.\n\n#deporte\n",
            "# Lentejas de la abuela\n\nLlevan comino, una hoja de laurel y un chorizo. 40 minutos en olla normal, 12 en la exprés.\n\n#recetas\n",
            "# Laptop setup\n\nFedora 41 with GNOME. Backups run with borg to [[NAS]] every night at 02:30.\n\n#tech\n",
            "# NAS\n\nSynology DS224+ called vault-nas, at 192.168.1.40. Two 8 TB disks in RAID 1.\n\n#tech #casa\n",
            "# Book list 2026\n\nRead this year: Piranesi, The Dispossessed. Up next: Stoner.\n\n#books\n",
            "# Alergias\n\nSoy alérgico a la penicilina. Llevo siempre la tarjeta de alergias en la cartera.\n\n#salud\n",
            "# Trip to Lisbon\n\nFlight TP1023 on 14 March. Hotel near Alfama, booking code KX7Q2.\n\n#travel\n",
            "# Plants\n\nWater the monstera every 10 days. The basil needs water daily in summer.\n\n#casa\n",
            "# Project Heron\n\nHeron is the billing migration at work. Deadline: 30 January. Contact: Priya in finance.\n\n#work\n",
            "# Bicicleta\n\nLa bici de carretera usa cubiertas 700x28 a 6 bares de presión.\n\n#deporte\n",
            "# Gym locker\n\nLocker 117 at the north gym. Combination 4-8-15.\n\n#deporte\n",
            "# Seguro de casa\n\nPóliza con Aseguradora Norte, número HN-55120. Se renueva en mayo.\n\n#casa\n",
            "# Coffee grinder\n\nGrind setting 14 for the V60 and 6 for espresso.\n\n#habits\n",
            "# Passport\n\nMy passport expires on 2029-08-03. Renew it six months before.\n\n#travel\n",
            "# Cumpleaños\n\nMarta: 12 de abril. Tomás: 3 de octubre, le gustan los puzles.\n\n#familia\n",
            "# Kubernetes\n\nA pod is the smallest unit. A deployment keeps a number of pods running. kubectl get pods lists them.\n\n#tech\n",
            "# Docker cheat sheet\n\ndocker ps lists running containers. docker compose up -d starts a stack in the background.\n\n#tech\n",
        )

        val COVERED = listOf(
            Covered("¿Cómo se llama mi dentista?", "Dentista", "Okafor"),
            Covered("When is the next oil change for the car?", "Car service", "94[,.]?000"),
            Covered("¿Qué días hago fuerza?", "Plan de entrenamiento", "lunes.*jueves|jueves.*lunes"),
            Covered("What grind setting do I use for espresso?", "Coffee grinder", "\\b6\\b"),
            Covered("¿Qué lleva la receta de lentejas de la abuela?", "Lentejas de la abuela", "comino"),
            Covered("What is the IP address of my NAS?", "NAS", "192\\.168\\.1\\.40"),
            Covered("When do my laptop backups run?", "Laptop setup", "2:30"),
            Covered("¿A qué soy alérgico?", "Alergias", "penicilina"),
            Covered("What's the booking code for the Lisbon hotel?", "Trip to Lisbon", "KX7Q2"),
            Covered("How often should I water the monstera?", "Plants", "10 days|ten days"),
            Covered("Who is my contact for Heron?", "Project Heron", "Priya"),
            Covered("¿Qué presión llevan las ruedas de la bici?", "Bicicleta", "\\b6\\b"),
            Covered("What's my locker combination?", "Gym locker", "4-8-15"),
            Covered("¿Cuál es el número de la póliza del seguro de casa?", "Seguro de casa", "HN-55120"),
            Covered("When does my passport expire?", "Passport", "2029"),
            Covered("¿Cuándo es el cumpleaños de Tomás?", "Cumpleaños", "octubre"),
            // No word of the note's title: the search has to go by meaning.
            Covered("What book am I going to read next?", "Book list 2026", "Stoner"),
            Covered("How much storage do the disks of the machine my laptop backs up to have?", "NAS", "8 ?TB"),
        )

        val UNCOVERED = listOf(
            Uncovered("¿Cómo se llama mi veterinario?", "\\b(Dr|Dra)\\.? [A-Z]"),
            Uncovered("What is my car's license plate?", "\\b(?=[A-Z0-9-]*\\d)[A-Z0-9]{2,4}-[A-Z0-9]{2,4}\\b"),
            Uncovered("What's the wifi password at home?", "password (is|:)"),
            Uncovered("¿Cuándo es el cumpleaños de Lucía?", "Luc[ií]a\\b[^.]{0,30}\\b\\d{1,2} de [a-z]+"),
            Uncovered("What time does my Lisbon flight leave?", "\\b\\d{1,2}[:.]\\d{2}\\b|\\b\\d{1,2} ?(am|pm)\\b"),
            Uncovered("What is my gym membership number?", "\\bnumber is:? *#?\\d|#\\d{3,}|\\b117\\b"),
        )
    }
}
