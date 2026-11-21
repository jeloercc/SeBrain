package com.sebrain.app

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.ml.ChatEngine
import com.sebrain.core.ml.ModelFiles
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Can the chat model take a larger context window, and does it still find what is in it? For each
 * window the model is loaded with that `maxNumTokens` and given synthetic notes that nearly fill it,
 * with one fact buried at 30% of the text ("needle in a haystack"). Logged under SEBRAIN_EVAL:
 * whether it loaded, whether the answer has the fact, tokens in, time to the first token, read
 * speed and the process memory. The test fails only if no model is installed.
 *
 * Arguments: `-e model <part of a file name>`, `-e windows 4096,8192,16384` (4096 loads with the
 * model's own default).
 */
@RunWith(AndroidJUnit4::class)
class ContextWindowEvalTest {
    private lateinit var chat: ChatEngine

    @After
    fun release() = chat.close()

    @Test
    fun windows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        chat = ChatEngine(context, ModelFiles(File(context.filesDir, "models")), benchmark = true)
        val models = chat.languageModels()
        assumeTrue("no language model is installed", models.isNotEmpty())
        val wanted = arguments.getString("model")
        val model = models.firstOrNull { wanted != null && it.fileName.contains(wanted, ignoreCase = true) } ?: models.first()
        val windows = (arguments.getString("windows") ?: "4096,8192,16384").split(',').map { it.trim().toInt() }

        for (window in windows) {
            val loaded = runCatching { chat.load(model, if (window == DEFAULT_WINDOW) null else window) }
            if (loaded.isFailure) {
                log("FAIL ${model.fileName} window=$window load: ${loaded.exceptionOrNull()?.message?.take(200)}")
                continue
            }
            val prompt = haystack(window)
            val start = System.nanoTime()
            val reply = runCatching { chat.reply(SYSTEM, emptyList(), prompt, REPLY_TOKENS) {} }
            val seconds = (System.nanoTime() - start) / 1e9
            val pssMb = Debug.getPss() / KB
            val stats = chat.lastStats
            val found = reply.getOrNull()?.contains(NEEDLE_CODE) == true
            val outcome = when {
                reply.isFailure -> "ERROR ${reply.exceptionOrNull()?.message?.take(160)}"
                found -> "PASS"
                else -> "MISSED reply=${reply.getOrNull()?.replace("\n", " ")?.take(120)}"
            }
            log(
                "${if (found) "PASS" else "FAIL"} ${model.fileName} backend=${chat.backend} window=$window " +
                    "chars=${prompt.length} tokens=${stats?.contextTokens} first=%.1f s prefill=%.0f tok/s total=%.1f s pss=%d MB: $outcome"
                        .format(stats?.firstTokenSeconds ?: 0.0, stats?.promptTokensPerSecond ?: 0.0, seconds, pssMb),
            )
        }
    }

    /** Synthetic notes that fill about [window] tokens less the reply, with the needle at 30%. */
    private fun haystack(window: Int): String {
        val targetChars = ((window - REPLY_TOKENS - OVERHEAD_TOKENS) * CHARS_PER_TOKEN).toInt()
        val notes = StringBuilder()
        var i = 0
        var needlePlaced = false
        while (notes.length < targetChars) {
            if (!needlePlaced && notes.length >= targetChars * NEEDLE_DEPTH) {
                notes.append(NEEDLE).append('\n')
                needlePlaced = true
            }
            notes.append(FILLER[i % FILLER.size].format(i, i % ROOMS, i % DAYS + 1)).append('\n')
            i++
        }
        return "Here are my notes:\n\n$notes\nQuestion: $QUESTION Answer with the code only."
    }

    private fun log(line: String) {
        Log.i("SEBRAIN_EVAL", line)
    }

    private companion object {
        const val DEFAULT_WINDOW = 4096
        const val REPLY_TOKENS = 32
        /** The system line and the question, with room to spare. */
        const val OVERHEAD_TOKENS = 200
        /** Lower than the 3.6 measured for prose, so the synthetic text does not overflow the window. */
        const val CHARS_PER_TOKEN = 3.0
        const val NEEDLE_DEPTH = 0.3
        const val ROOMS = 37
        const val DAYS = 28
        const val KB = 1024
        const val SYSTEM = "You answer only from the user's notes."
        const val QUESTION = "What is the access code for the boathouse?"
        const val NEEDLE_CODE = "7341"
        const val NEEDLE = "Note: the access code for the boathouse is 7341."
        val FILLER = listOf(
            "Note %d: the water filter in room %d was replaced on day %d.",
            "Note %d: the meeting about the garden budget moved to room %d on day %d.",
            "Note %d: a parcel for the workshop was left in room %d on day %d.",
            "Note %d: the heating in room %d made a noise again on day %d.",
            "Note %d: the library returned two books to room %d on day %d.",
            "Note %d: the bicycle lock code for room %d changed on day %d.",
        )
    }
}
