package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.Assistant
import com.sebrain.app.chat.ContextNote
import com.sebrain.app.chat.RelevanceGate
import com.sebrain.app.chat.Route
import com.sebrain.app.chat.Routing
import com.sebrain.app.chat.SemanticRouter
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
 * The vault also has near-identical notes (two trips, a car and a van), a fact that changed
 * (the newer note must win) and questions with typos. `-e filler 300` adds that many generated
 * notes, with the same words as the real ones, to see whether search holds at the size of a real
 * vault; the time each search takes is logged too.
 *
 * The test fails only if no model is installed. Arguments: `-e model <part of a file name>`,
 * `-e cases 1,5,…` (case numbers, to rerun some), `-e filler <count>` (default 0).
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

    /** A general question: the reply must have [fact] and must not talk about the notes. */
    private class General(val question: String, val fact: String)

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
        val filler = arguments.getString("filler")?.toIntOrNull() ?: 0
        val all = NOTES.map { it to DEFAULT_DATE } + DATED + Filler.notes(filler)
        val indexStart = System.nanoTime()
        vault.session.withDatabase { db ->
            all.forEach { (text, date) ->
                val id = NoteStore(db).create(text, NoteIndexer.sha256Hex(text), date, "UTC")!!
                indexer.index(db, id, text)
            }
        }
        val indexSeconds = (System.nanoTime() - indexStart) / 1e9
        chat.load(model)
        log(
            "eval trust model=${model.fileName} backend=${chat.backend} embeddings=${embedder.isAvailable} notes=${all.size} " +
                "filler=$filler indexed in %.1f s".format(indexSeconds),
        )

        val only = arguments.getString("cases")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        var found = 0
        var answered = 0
        var cited = 0
        var honest = 0
        var covered = 0
        var uncovered = 0
        var general = 0
        var generalOk = 0
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
                "${if (ok) "PASS" else "FAIL"} covered $number route=$route found=${source?.number ?: "no"}/${notes.size} fact=$hasFact cited=$citesSource " +
                    "dist=${distance(case.question, case.source)?.let { "%.3f".format(it) }} " +
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
            // "Your notes don't have the vet; they do have your dentist, Dr. Okafor" is the asked-for
            // behaviour (Assistant.EXACT_FACT): a neighbouring fact is invention only when the gap is not admitted.
            val invents = !admits && case.invented != null && Regex(case.invented, RegexOption.IGNORE_CASE).containsMatchIn(reply)
            val ok = admits && !invents
            if (ok) honest++
            log("${if (ok) "PASS" else "FAIL"} uncovered $number route=$route nearest=${distance(case.question, null)?.let { "%.3f".format(it) }} admits=$admits invents=$invents q=\"${case.question}\" reply=${reply.oneLine()}")
        }
        // 2026-11-20: a rule for personal facts made E2B answer "nothing about NASA in your notes".
        GENERAL.forEachIndexed { i, case ->
            val number = COVERED.size + UNCOVERED.size + i + 1
            if (only != null && number !in only) return@forEachIndexed
            general++
            val reply = ask(case.question, notesFor(case.question))
            val hasFact = Regex(case.fact, RegexOption.IGNORE_CASE).containsMatchIn(reply)
            val talksOfNotes = Regex(NOTES_WORD, RegexOption.IGNORE_CASE).containsMatchIn(reply)
            val ok = hasFact && !talksOfNotes
            if (ok) generalOk++
            log("${if (ok) "PASS" else "FAIL"} general $number route=$route nearest=${distance(case.question, null)?.let { "%.3f".format(it) }} fact=$hasFact talksOfNotes=$talksOfNotes q=\"${case.question}\" reply=${reply.oneLine()}")
        }
        val sorted = searchMs.sorted()
        log(
            "eval trust model=${model.fileName} notes=${all.size} found=$found/$covered answered=$answered/$covered cited=$cited/$covered " +
                "honest=$honest/$uncovered general=$generalOk/$general search median=${sorted.getOrNull(sorted.size / 2)} ms max=${sorted.lastOrNull()} ms",
        )
    }

    /** Milliseconds each search took, query embedding included. */
    private val searchMs = mutableListOf<Long>()

    /**
     * For calibrating the relevance gate: the vector distance of [title]'s closest chunk, or of
     * the closest chunk of any note when [title] is null. Null without embeddings.
     */
    private fun distance(question: String, title: String?): Double? = vault.session.withDatabase { db ->
        if (!embedder.isAvailable) return@withDatabase null
        val hits = com.sebrain.core.vault.HybridSearch(db).search(question, embedder.embedQuery(question), DISTANCE_HITS)
        val store = NoteStore(db)
        hits.filter { title == null || store.get(it.captureId)?.title == title }.mapNotNull { it.vectorDistance }.minOrNull()
    }

    /** The router the app builds, from the same embedding model; null without one. */
    private val router by lazy { if (embedder.isAvailable) SemanticRouter.build(embedder::embedQuery) else null }

    /** The route of the last [notesFor], for the prompt; the app passes it the same way. */
    private var route = Route.GENERAL

    /** What ASK does: route the message, then search with that route's relevance gate. */
    private fun notesFor(question: String): List<ContextNote> = vault.session.withDatabase { db ->
        val start = System.nanoTime()
        val vector = if (embedder.isAvailable) embedder.embedQuery(question) else null
        route = Routing.route(question, vector, router)
        VaultContext.build(db, question, vector, maxDistance = RelevanceGate.maxDistance(route)).notes
            .also { searchMs += (System.nanoTime() - start) / NANOS_PER_MS }
    }

    /** The reply ASK would show: same prompt, system line, token limit and citation clean-up. */
    private fun ask(question: String, notes: List<ContextNote>): String {
        val prompt = Assistant.prompt(question, notes, asNote = false, tags = emptyList(), route = route)
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
        const val DISTANCE_HITS = 50

        /** A general answer has no reason to bring up the notes. */
        const val NOTES_WORD = "\\b(notes?|notas?|vault)\\b"

        val GENERAL = listOf(
            General("¿Quién fue el primer fotógrafo?", "Ni[eé]pce|Daguerre"),
            General("¿Qué me puedes decir de la NASA?", "espacia|space|agencia"),
            General("What is the capital of Australia?", "Canberra"),
            General("What is DNS?", "domain|name system"),
            General("Explica la fotosíntesis en una frase", "luz|sol|energ"),
            General("hola, ¿cómo estás?", "\\S"),
        )
        const val NANOS_PER_MS = 1_000_000L
        const val DEFAULT_DATE = "2026-11-15T10:00:00Z"

        /** Notes with their own date: near-identical ones, and a fact that changed. */
        val DATED = listOf(
            "# Trip to Lisbon 2024\n\nThe October 2024 trip. Flight TP1350, hotel in Baixa, booking code ZR4M8.\n\n#travel\n" to "2024-10-02T09:00:00Z",
            "# Van service\n\nThe Transit van had its oil change at 120,500 km. The next one is due at 131,000 km.\n\n#car\n" to "2026-09-20T09:00:00Z",
            // Newer than "Gym locker": the combination changed.
            "# Gym\n\nSince November I use locker 204 at the north gym. New combination 9-2-6.\n\n#deporte\n" to "2026-11-18T09:00:00Z",
        )

        /** Ways a reply says the notes do not have the answer, in English and Spanish. */
        const val ADMITS =
            "(notes?|vault)\\b[^.]{0,40}\\b(do(es)?n['’]?t|do(es)? not|not|no)\\b|" +
                "\\b(not|no|nothing)\\b[^.]{0,40}\\b(notes?|vault)\\b|" +
                "(don['’]?t|do not|does not|doesn['’]?t) (have|know|see|specify|say|mention|include|list|contain)|no information|" +
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
            Covered("What's the booking code for the Lisbon hotel in March?", "Trip to Lisbon", "KX7Q2"),
            Covered("How often should I water the monstera?", "Plants", "10 days|ten days"),
            Covered("Who is my contact for Heron?", "Project Heron", "Priya"),
            Covered("¿Qué presión llevan las ruedas de la bici?", "Bicicleta", "\\b6\\b"),
            // Two notes say it; the newer one is right.
            Covered("What's my locker combination now?", "Gym", "9-2-6"),
            Covered("¿Cuál es el número de la póliza del seguro de casa?", "Seguro de casa", "HN-55120"),
            Covered("When does my passport expire?", "Passport", "2029"),
            Covered("¿Cuándo es el cumpleaños de Tomás?", "Cumpleaños", "octubre"),
            // No word of the note's title: the search has to go by meaning.
            Covered("What book am I going to read next?", "Book list 2026", "Stoner"),
            Covered("How much storage do the disks of the machine my laptop backs up to have?", "NAS", "8 ?TB"),
            // Near-identical notes.
            Covered("When is the van's next oil change?", "Van service", "131[,.]?000"),
            Covered("What was the booking code of my Lisbon trip in October 2024?", "Trip to Lisbon 2024", "ZR4M8"),
            // Typos.
            Covered("wen do my lapotp backups run", "Laptop setup", "2:30"),
            Covered("cuando caduca mi pasaprote", "Passport", "2029"),
            // The answer needs two notes.
            Covered("What is the IP of the machine my laptop backs up to, and at what time does the backup run?", "Laptop setup", "(?s)(192\\.168\\.1\\.40.*2:30|2:30.*192\\.168\\.1\\.40)"),
        )

        val UNCOVERED = listOf(
            Uncovered("¿Cómo se llama mi veterinario?", "\\b(Dr|Dra)\\.? [A-Z]"),
            Uncovered("What is my car's license plate?", "\\b(?=[A-Z0-9-]*\\d)[A-Z0-9]{2,4}-[A-Z0-9]{2,4}\\b"),
            Uncovered("What's the wifi password at home?", "password (is|:)"),
            Uncovered("¿Cuándo es el cumpleaños de Lucía?", "Luc[ií]a\\b[^.]{0,30}\\b\\d{1,2} de [a-z]+"),
            Uncovered("What time does my Lisbon flight leave?", "\\b\\d{1,2}[:.]\\d{2}\\b|\\b\\d{1,2} ?(am|pm)\\b"),
            Uncovered("What is my gym membership number?", "\\bnumber is:? *#?\\d|#\\d{3,}|\\b(117|204)\\b"),
        )
    }
}
