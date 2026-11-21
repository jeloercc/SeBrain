package com.sebrain.app

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.cognition.Extraction
import com.sebrain.core.ml.ChatEngine
import com.sebrain.core.ml.ModelFiles
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The extraction model benchmark of CLAUDE.md: which model the `CognitionWorker` should use.
 * 30 made-up captures with known people, dates and tasks go through [Extraction] exactly as the
 * worker will send them: the capture date as reference, constrained decoding with the §6 schema,
 * the whitespace guard, and one retry with the corrective note.
 *
 * Logged under SEBRAIN_EVAL: valid JSON (first try and with the retry), people found, dates
 * resolved to the right day, tasks found, time per capture (mean and p95), the cost of creating
 * a conversation with constrained decoding, and the process memory. Arguments: `-e model <part of
 * a file name>`. Fails only if no model is installed.
 */
@RunWith(AndroidJUnit4::class)
class ExtractionEvalTest {
    private lateinit var chat: ChatEngine

    /** [date] is the day the capture's main date must resolve to, as `yyyy-MM-dd`. */
    private class Capture(val text: String, val people: List<String>, val date: String?, val task: String?)

    @After
    fun release() {
        if (::chat.isInitialized) chat.close()
    }

    @Test
    fun extraction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        chat = ChatEngine(context, ModelFiles(File(context.filesDir, "models")), benchmark = true)
        val models = chat.languageModels()
        assumeTrue("no language model is installed", models.isNotEmpty())
        val wanted = InstrumentationRegistry.getArguments().getString("model")
        val model = models.firstOrNull { wanted != null && it.fileName.contains(wanted, ignoreCase = true) } ?: models.first()
        val loadStart = System.nanoTime()
        chat.load(model)
        log("eval extraction model=${model.fileName} backend=${chat.backend} load=%.1f s".format((System.nanoTime() - loadStart) / 1e9))

        val tally = Tally()
        CAPTURES.forEachIndexed { i, capture -> tally.add(i + 1, capture, extract(capture)) }
        log("eval extraction model=${model.fileName} ${tally.summary()} pss=${Debug.getPss() / KB} MB")
    }

    private class Run(val outcome: Extraction.Outcome, val attempts: Int, val ms: List<Double>, val createMs: List<Double>, val raw: String)

    /** One capture as the worker will do it ([Extraction.extract]), with each attempt timed. */
    private fun extract(capture: Capture): Run {
        val ms = mutableListOf<Double>()
        val create = mutableListOf<Double>()
        var raw = ""
        val result = runCatching {
            Extraction.extract(capture.text, CAPTURED_AT, ZONE) { message ->
                val start = System.nanoTime()
                val reply = chat.structured(Extraction.SYSTEM, message, Extraction.SCHEMA, Extraction.MAX_TOKENS, Extraction::inWhitespaceLoop)
                ms += (System.nanoTime() - start) / NANOS_PER_MS
                create += reply.createMs
                raw = reply.text
                Extraction.Reply(reply.text, reply.cut)
            }
        }
        val outcome = result.getOrNull()?.outcome ?: Extraction.Outcome.Invalid(Extraction.Failure.JSON_INVALID)
        if (result.isFailure) raw = "ERROR ${result.exceptionOrNull()?.message?.take(100)}"
        return Run(outcome, result.getOrNull()?.attempts ?: 2, ms, create, raw)
    }

    private inner class Tally {
        private var firstTry = 0
        private var valid = 0
        private var peopleFound = 0
        private var peopleTotal = 0
        private var datesRight = 0
        private var datesTotal = 0
        private var tasksFound = 0
        private var tasksTotal = 0
        private val perCapture = mutableListOf<Double>()
        private val create = mutableListOf<Double>()

        fun add(number: Int, capture: Capture, run: Run) {
            perCapture += run.ms.sum()
            create += run.createMs
            val fields = (run.outcome as? Extraction.Outcome.Valid)?.fields
            if (fields != null) valid++
            if (fields != null && run.attempts == 1) firstTry++
            val people = capture.people.count { name -> fields?.people.orEmpty().any { plain(it).contains(plain(name)) } }
            peopleFound += people
            peopleTotal += capture.people.size
            val dateRight = capture.date != null && fields?.dates.orEmpty().any { it.contains(capture.date) }
            if (capture.date != null) datesTotal++
            if (dateRight) datesRight++
            val taskFound = capture.task != null && fields?.tasks.orEmpty().any { plain(it).contains(plain(capture.task)) }
            if (capture.task != null) tasksTotal++
            if (taskFound) tasksFound++
            log(
                "capture $number ${if (fields != null) "valid" else (run.outcome as Extraction.Outcome.Invalid).failure.code} attempts=${run.attempts} " +
                    "people=$people/${capture.people.size} date=${if (capture.date == null) "-" else dateRight} task=${if (capture.task == null) "-" else taskFound} " +
                    "ms=%.0f raw=${run.raw.replace("\n", " ").take(RAW_LOGGED)}".format(run.ms.sum()),
            )
        }

        fun summary(): String {
            val sorted = perCapture.sorted()
            return "valid=$valid/${CAPTURES.size} firstTry=$firstTry people=$peopleFound/$peopleTotal dates=$datesRight/$datesTotal " +
                "tasks=$tasksFound/$tasksTotal mean=%.0f ms p95=%.0f ms createConversation mean=%.0f ms"
                    .format(sorted.average(), sorted[(sorted.size * P95).toInt().coerceAtMost(sorted.size - 1)], create.average())
        }
    }

    /** Lower case without accents, so "Rocío" matches "rocio". */
    private fun plain(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    private fun log(line: String) {
        Log.i("SEBRAIN_EVAL", line)
    }

    private companion object {
        const val KB = 1024
        const val NANOS_PER_MS = 1e6
        const val P95 = 0.95
        const val RAW_LOGGED = 200

        /** Saturday 7 November 2026, 21:24 in Mexico City: the reference for every relative date. */
        val CAPTURED_AT: Instant = Instant.parse("2026-11-08T03:24:00Z")
        val ZONE: ZoneId = ZoneId.of("America/Mexico_City")

        val CAPTURES = listOf(
            Capture("WhatsApp · Laura Méndez: la reunión con diseño pasa al jueves a las 10:30. Trae los bocetos del onboarding.", listOf("Laura"), "2026-11-12", "bocetos"),
            Capture("Gmail · Banco Central: su factura de la tarjeta por 1.245,90 EUR vence el 28 de noviembre.", emptyList(), "2026-11-28", null),
            Capture("Recordatorio: cita con la dentista Dra. Patricia Ruiz mañana a las 17:15. Llevar la tarjeta del seguro.", listOf("Patricia Ruiz"), "2026-11-08", "tarjeta"),
            Capture("Telegram · Marcos: el sábado 21 subimos a La Pedriza. Ana lleva la cuerda de 70 m.", listOf("Marcos", "Ana"), "2026-11-21", "cuerda"),
            Capture("Slack · Javier Ortega: el despliegue de la 4.2 se retrasa al lunes 16. Rocío está revisando el script.", listOf("Javier Ortega", "Rocío"), "2026-11-16", "script"),
            Capture("Correo de Elena Gutiérrez: el cliente quiere el presupuesto revisado antes del 20 de noviembre. Pablo se encarga de lo legal.", listOf("Elena Gutiérrez", "Pablo"), "2026-11-20", "presupuesto"),
            Capture("Mamá: mañana domingo es el cumpleaños de la abuela Carmen. ¿Puedes encargar la tarta?", listOf("Carmen"), "2026-11-08", "tarta"),
            Capture("Email from Priya Shah: the Heron review moved to next Friday at 3 pm. Please send the slides before then.", listOf("Priya Shah"), "2026-11-13", "slides"),
            Capture("Tom: dinner at Marco's place tomorrow at 8, bring a bottle of wine.", listOf("Tom", "Marco"), "2026-11-08", "wine"),
            Capture("Dentist reminder: appointment with Dr. Okafor on the 20th at 9:00.", listOf("Okafor"), "2026-11-20", null),
            Capture("Calendar: flight to Lisbon on March 14, TP1023, seat 14C.", emptyList(), "2027-03-14", null),
            Capture("Nota: renovar el pasaporte antes del 5 de enero.", emptyList(), "2027-01-05", "pasaporte"),
            Capture("Carla: ¿me devuelves el libro el miércoles?", listOf("Carla"), "2026-11-11", "libro"),
            Capture("Ben Carter: the plumber comes on Monday morning; leave the key with Grace.", listOf("Ben Carter", "Grace"), "2026-11-09", "key"),
            Capture("Grupo familia · Tía Rosa: comemos en su casa el 3 de diciembre a las 14:00.", listOf("Rosa"), "2026-12-03", null),
            Capture("Hugo: call the insurance about claim HN-55120 before Wednesday.", listOf("Hugo"), "2026-11-11", "insurance"),
            Capture("Artículo compartido: Anneke de Vries publica un estudio sobre la fermentación láctica de la col.", listOf("Anneke de Vries"), null, null),
            Capture("Diego Ramos: pásame los colores del logo antes del martes.", listOf("Diego Ramos"), "2026-11-10", "colores"),
            Capture("School: parents' meeting with Ms. Johnson on November 25 at 6 pm.", listOf("Johnson"), "2026-11-25", null),
            Capture("Ana: no olvides pagar el alquiler el 1 de diciembre.", listOf("Ana"), "2026-12-01", "alquiler"),
            Capture("Femi: the car service is booked for tomorrow at the garage on Mill Road.", listOf("Femi"), "2026-11-08", null),
            Capture("Lista de la compra: tomate, arroz, comino y laurel.", emptyList(), null, null),
            Capture("Jon Ibarra: el partido es el viernes a las 19:00, lleva las zapatillas.", listOf("Jon Ibarra"), "2026-11-13", "zapatillas"),
            Capture("Sofia Rossi: I land in Madrid on the 18th at 7:40, can you pick me up at the airport?", listOf("Sofia Rossi"), "2026-11-18", "pick"),
            Capture("Inés: la revisión del coche es el 30 de noviembre en el taller de la calle Mayor.", listOf("Inés"), "2026-11-30", null),
            Capture("Reminder from Kai: return the projector to the office by Tuesday.", listOf("Kai"), "2026-11-10", "projector"),
            Capture("Nota de voz: idea para el blog sobre EmbeddingGemma y la búsqueda híbrida.", emptyList(), null, null),
            Capture("Maria and Paul: housewarming party on Saturday the 28th, bring snacks.", listOf("Maria", "Paul"), "2026-11-28", "snacks"),
            Capture("Banco: transferencia de 300 EUR recibida de Lucas Pérez el 6 de noviembre.", listOf("Lucas Pérez"), "2026-11-06", null),
            Capture("Grace Lee: the contract must be signed by December 10; send it to legal.", listOf("Grace Lee"), "2026-12-10", "contract"),
        )
    }
}
