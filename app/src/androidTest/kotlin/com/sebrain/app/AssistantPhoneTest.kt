package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.Assistant
import com.sebrain.app.chat.ContextNote
import com.sebrain.app.chat.NoteFormat
import com.sebrain.core.ml.ChatEngine
import com.sebrain.core.ml.ModelFiles
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the assistant with the language model installed on the phone. It reads the model the
 * user installed but never opens the real vault: saved notes are simulated in memory.
 *
 * Results go to logcat under SEBRAIN_EVAL, for a person to read; the tests only fail if the
 * model cannot run at all. Run with `am instrument`, never `connectedAndroidTest`, which
 * uninstalls the app and its data.
 */
@RunWith(AndroidJUnit4::class)
class AssistantPhoneTest {
    private lateinit var chat: ChatEngine

    @Before
    fun loadModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        chat = ChatEngine(context, ModelFiles(File(context.filesDir, "models")), benchmark = true)
        val models = chat.languageModels()
        assumeTrue("no language model is installed", models.isNotEmpty())
        val saved = context.getSharedPreferences("sebrain", 0).getString("language_model", null)
        val model = models.firstOrNull { it.fileName == saved } ?: models.first()
        val start = System.nanoTime()
        chat.load(model)
        log("model=${model.fileName} backend=${chat.backend} load=${(System.nanoTime() - start) / 1_000_000} ms")
    }

    @After
    fun release() = chat.close()

    /** The conversation that repeated notes on 2026-11-12, with the vault simulated. */
    @Test
    fun kernelSequence() {
        val earlier = mutableListOf<Assistant.Line>()
        val vault = mutableListOf<Pair<String, String>>()
        val requests = listOf(
            "Lista los tipos de kernel de sistemas operativos, solo los nombres",
            "haz una nota del primero",
            "explicame y haz la nota del siguiente",
            "haz la nota del siguiente",
            "cual es el siguiente kernel que falta?",
        )
        for ((turn, request) in requests.withIndex()) {
            val memory = Assistant.remember(earlier)
            val asNote = Assistant.wantsNote(request)
            val exclude = (if (asNote) memory.covered else memory.recent.filter { !it.fromUser }.mapNotNull { Assistant.heading(it.text) })
                .map { it.lowercase() }
            val notes = vault.filter { it.first.lowercase() !in exclude }
                .mapIndexed { i, (title, text) -> ContextNote(i + 1, i.toLong(), title, emptyList(), emptyList(), emptyList(), text) }
            val prompt = Assistant.prompt(request, notes, asNote, emptyList(), memory)
            val start = System.nanoTime()
            val reply = chat.reply(Assistant.SYSTEM, memory.recent.map { ChatEngine.Turn(it.fromUser, it.text) }, prompt) {}
            val seconds = (System.nanoTime() - start) / 1e9
            log("turn=${turn + 1} request=\"$request\" asNote=$asNote promptChars=${prompt.length} history=${memory.recent.size}")
            log("turn=${turn + 1} seconds=%.1f replyChars=${reply.length} stats: ${chat.lastStats}".format(seconds))
            log("turn=${turn + 1} heading=${Assistant.heading(reply)} reply=${reply.replace('\n', ' ').take(260)}")
            earlier += Assistant.Line(true, request)
            earlier += Assistant.Line(false, reply)
            if (asNote) {
                NoteFormat.parse(reply, request).forEach { vault += it.title to it.body }
            }
        }
        log("titles written: ${vault.map { it.first }}")
    }

    /** Finds how much prompt the model accepts, by doubling a filler text until it fails. */
    @Test
    fun contextLimit() {
        val sentence = "The quick brown fox jumps over the lazy dog near the river bank. "
        var chars = 2_000
        while (chars <= 64_000) {
            val prompt = sentence.repeat(chars / sentence.length) + "\n\nReply with the single word OK."
            val result = runCatching { chat.reply("You are a test assistant.", emptyList(), prompt) {} }
            log(
                "context promptChars=${prompt.length} " +
                    result.fold({ "ok reply=\"${it.take(40).replace('\n', ' ')}\" stats: ${chat.lastStats}" }, { "FAILED ${it.javaClass.simpleName}: ${it.message?.take(160)}" }),
            )
            if (result.isFailure) break
            chars *= 2
        }
    }

    /** One note, then several in one request: what the model writes and what is saved after cleaning. */
    @Test
    fun noteFormat() {
        for (request in listOf(
            "haz una nota sobre el protocolo TCP",
            "crea una nota para cada uno de estos protocolos: UDP, QUIC y HTTP/3",
        )) {
            val start = System.nanoTime()
            val reply = chat.reply(Assistant.SYSTEM, emptyList(), Assistant.prompt(request, emptyList(), true, listOf("redes", "protocolos"))) {}
            log("format request=\"$request\" seconds=%.1f stats: ${chat.lastStats}".format((System.nanoTime() - start) / 1e9))
            log("format raw=${reply.replace("\n", "⏎")}")
            NoteFormat.parse(reply, request).forEachIndexed { i, note ->
                log("format note ${i + 1}: title=\"${note.title}\" tags=${note.tags} body=${note.body.replace("\n", "⏎")}")
            }
        }
    }

    /** The long system instruction against the compact one, on the message that made a small model write its instructions back. */
    @Test
    fun echo() {
        // The message of the screenshot of 2026-11-13, which the model answered with its instructions.
        val prompt = Assistant.prompt("revisa conceptos no enlazados en todas nuestras notas", emptyList(), false, emptyList())
        for ((name, system) in listOf("long" to Assistant.SYSTEM, "compact" to Assistant.SYSTEM_COMPACT)) {
            val start = System.nanoTime()
            val reply = chat.reply(system, emptyList(), prompt, 120) {}
            log("echo $name seconds=%.1f echoes=${Assistant.echoes(reply)} looped=${chat.lastLooped} chars=${reply.length} text=${reply.replace('\n', ' ').take(260)}".format((System.nanoTime() - start) / 1e9))
        }
    }

    /** The conversation of the screenshots of 2026-11-14: a note, in English, about the answer just given. */
    @Test
    fun noteOfPreviousAnswer() {
        val answer = "Para mejorar la conversación entre los conceptos, se podría añadir un enfoque más detallado sobre cómo las arquitecturas " +
            "de kernel (como el Monolithic Kernel frente al Microkernel) interactúan en escenarios prácticos, especialmente en cómo se " +
            "implementa la delegación de servicios (como la asignación de memoria o la gestión de drivers) en un entorno Microkernel.\n\n" +
            "Se podría introducir una comparativa más explícita entre la implementación de la delegación en ambos tipos de kernel."
        val earlier = listOf(
            Assistant.Line(true, "revisa conceptos no enlazados en todas nuestras notas"),
            Assistant.Line(true, "que mas nos hace falta hablar de kernels"),
            Assistant.Line(false, answer),
        )
        val memory = Assistant.remember(earlier)
        val request = "perfect haz una nota en Ingles de tu sujerencia"
        val prompt = Assistant.prompt(request, emptyList(), true, listOf("kernel", "os"), memory, previousAnswer = answer)
        val start = System.nanoTime()
        val reply = chat.reply(Assistant.SYSTEM_COMPACT, memory.recent.map { ChatEngine.Turn(it.fromUser, it.text) }, prompt, Assistant.NOTE_TOKENS) {}
        log("previous seconds=%.1f language=${Assistant.language(reply.take(600))} looped=${chat.lastLooped}".format((System.nanoTime() - start) / 1e9))
        log("previous raw=${reply.replace("\n", "⏎").take(900)}")
        NoteFormat.parse(reply, request).forEach { log("previous note title=\"${it.title}\" tags=${it.tags} body=${it.body.replace("\n", "⏎").take(500)}") }
    }

    /** What the engine does when a reply reaches its token limit: stop quietly, or fail. */
    @Test
    fun outputCap() {
        val start = System.nanoTime()
        val result = runCatching { chat.reply("You are a storyteller.", emptyList(), "Tell a very long story about a lighthouse keeper.", 40) {} }
        log("cap seconds=%.1f outcome=${result.fold({ "ok chars=${it.length} text=${it.replace('\n', ' ').take(200)}" }, { "FAILED ${it.javaClass.simpleName}: ${it.message?.take(200)}" })} stats: ${chat.lastStats}".format((System.nanoTime() - start) / 1e9))
    }

    /** The tools on an existing note: what the model returns and what the app would propose. */
    @Test
    fun noteTools() {
        val note = "---\nstatus: active\n---\n# Protocolo SSH\n\nSSH es un protocolo para conectarse a otra maquina de forma segura. " +
            "Usa cifrado asimetrico para el intercanbio de claves y despues cifrado simetrico. Ver [[Criptografía]].\n\n" +
            "Se usa mucho para administrar servidores y para copiar archibos con scp.\n\n```bash\nssh usuario@servidor\n```\n\n#redes #pinned\n"
        val body = com.sebrain.cognition.FrontMatter.body(note)
        fun run(kind: com.sebrain.app.chat.NoteEdits.Kind, instruction: String = "") {
            val system = when (kind) {
                com.sebrain.app.chat.NoteEdits.Kind.FIX -> com.sebrain.app.chat.NoteEdits.FIX_SYSTEM
                com.sebrain.app.chat.NoteEdits.Kind.TAGS -> com.sebrain.app.chat.NoteEdits.TAGS_SYSTEM
                else -> com.sebrain.app.chat.NoteEdits.SYSTEM
            }
            val start = System.nanoTime()
            val reply = chat.reply(
                system, emptyList(), com.sebrain.app.chat.NoteEdits.prompt(kind, body, instruction, listOf("redes", "seguridad", "protocolos")),
                if (com.sebrain.app.chat.NoteEdits.isAddition(instruction)) com.sebrain.app.chat.NoteEdits.ADDITION_TOKENS else com.sebrain.app.chat.NoteEdits.maxTokens(kind, body.length),
            ) {}
            val seconds = (System.nanoTime() - start) / 1e9
            val result = when (kind) {
                com.sebrain.app.chat.NoteEdits.Kind.FIX -> com.sebrain.app.chat.NoteEdits.corrected(note, reply)
                com.sebrain.app.chat.NoteEdits.Kind.TAGS -> com.sebrain.app.chat.NoteEdits.tagged(note, reply)
                else -> if (com.sebrain.app.chat.NoteEdits.isAddition(instruction)) com.sebrain.app.chat.NoteEdits.appended(note, reply) else com.sebrain.app.chat.NoteEdits.rewritten(kind, note, reply)
            }
            log("tool ${kind.name} seconds=%.1f estimate=${com.sebrain.app.chat.NoteEdits.secondsFor(kind, body.length)} stats: ${chat.lastStats}".format(seconds))
            log("tool ${kind.name} raw=${reply.replace("\n", "⏎").take(900)}")
            log("tool ${kind.name} warnings=${result?.warnings} changed=${result != null}")
            log("tool ${kind.name} result=${result?.text?.replace("\n", "⏎")?.take(1100)}")
        }
        run(com.sebrain.app.chat.NoteEdits.Kind.FIX)
        run(com.sebrain.app.chat.NoteEdits.Kind.TAGS)
        run(com.sebrain.app.chat.NoteEdits.Kind.EDIT, "añade a la nota Protocolo SSH que el puerto por defecto es el 22")
        run(com.sebrain.app.chat.NoteEdits.Kind.EDIT, "cambia el ejemplo para que use el puerto 2222")
        run(com.sebrain.app.chat.NoteEdits.Kind.IMPROVE)
    }

    private val script = listOf(
            true to "Estoy montando un servidor casero que se llama atlas, con Debian 12",
            false to "Perfecto. Para atlas con Debian 12 te recomiendo empezar por actualizar el sistema y crear un usuario sin privilegios.",
            true to "quiero usar docker y no podman",
            false to "De acuerdo, Docker. Instala docker-ce desde el repositorio oficial y añade tu usuario al grupo docker.",
            true to "los servicios que voy a poner son jellyfin, nextcloud y pihole",
            false to "Bien: Jellyfin para multimedia, Nextcloud para archivos y Pi-hole para bloquear publicidad por DNS. Cada uno en su contenedor.",
            true to "el disco de datos es de 4 TB y esta montado en /srv/datos",
            false to "Entonces los volúmenes de Jellyfin y Nextcloud deben apuntar a /srv/datos.",
            true to "falta decidir como hacer las copias de seguridad",
            false to "Pendiente: copias de seguridad. Opciones: restic a un disco externo o a otro equipo por Tailscale.",
    ).map { Assistant.Line(it.first, it.second) }

    /** Writes a summary of a scripted conversation, then asks for something only the summary holds. */
    @Test
    fun summary() {
        var start = System.nanoTime()
        val summary = chat.reply(Assistant.SUMMARY_SYSTEM, emptyList(), Assistant.summaryRequest(null, script)) {}.trim()
        log("summary seconds=%.1f words=${summary.split(Regex("\\s+")).size} stats: ${chat.lastStats}".format((System.nanoTime() - start) / 1e9))
        log("summary text=${summary.replace('\n', ' ')}")

        // Only the summary is given, as if every scripted message had left the recent window.
        val memory = Assistant.remember(script, summary, summarized = script.size).let { Assistant.Memory(emptyList(), it.outline, it.covered, it.summary) }
        val question = "como se llama mi servidor, donde esta el disco de datos y que quedo pendiente?"
        start = System.nanoTime()
        val reply = chat.reply(Assistant.SYSTEM, emptyList(), Assistant.prompt(question, emptyList(), false, emptyList(), memory)) {}
        log("recall seconds=%.1f reply=${reply.replace('\n', ' ').take(400)}".format((System.nanoTime() - start) / 1e9))
    }

    private fun log(line: String) {
        Log.i("SEBRAIN_EVAL", line)
    }
}
