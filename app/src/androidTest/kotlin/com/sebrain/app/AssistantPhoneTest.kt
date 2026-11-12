package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.Assistant
import com.sebrain.app.chat.ContextNote
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
                val (title, body, _) = Assistant.parseNote(reply, request)
                vault += title to body
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

    private fun log(line: String) {
        Log.i("SEBRAIN_EVAL", line)
    }
}
