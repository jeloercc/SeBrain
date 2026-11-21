package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.Assistant
import com.sebrain.app.chat.DraftNote
import com.sebrain.app.chat.NoteCheck
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
 * Does the model follow a request that builds on its previous answer? Every case starts from the
 * answer the Pixel gave on 2026-11-19 ("What is Linux") and sends one message. Checks are made by
 * code: what the note is about, that it does not just repeat the previous answer, how many notes,
 * the language. Results go to logcat under SEBRAIN_EVAL; the test fails only if no model can run.
 *
 * Arguments: `-e model <part of a file name>` picks the model; `-e followup off` leaves the
 * previous answer out of the prompt, as the app did before FollowUp, to measure the difference.
 */
@RunWith(AndroidJUnit4::class)
class FollowUpEvalTest {
    private class Check(val name: String, val passes: (reply: String, notes: List<DraftNote>) -> Boolean)

    private class Case(val name: String, val message: String, val checks: List<Check>)

    private lateinit var chat: ChatEngine
    private var followUp = true

    @Before
    fun loadModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        followUp = arguments.getString("followup") != "off"
        chat = ChatEngine(context, ModelFiles(File(context.filesDir, "models")), benchmark = true)
        val models = chat.languageModels()
        assumeTrue("no language model is installed", models.isNotEmpty())
        val wanted = arguments.getString("model")
        val model = models.firstOrNull { wanted != null && it.fileName.contains(wanted, ignoreCase = true) } ?: models.first()
        chat.load(model)
        log("MODEL ${model.fileName} backend=${chat.backend} followup=${if (followUp) "on" else "off"}")
    }

    @After
    fun release() = chat.close()

    // ---- Checks ----
    private fun has(vararg words: String) = Check("has ${words.joinToString("|")}") { reply, _ ->
        words.any { reply.contains(it, ignoreCase = true) }
    }
    private fun titled(vararg words: String) = Check("titled ${words.joinToString("|")}") { _, notes ->
        notes.isNotEmpty() && words.any { word -> notes.first().title.contains(word, ignoreCase = true) }
    }
    private fun noteCount(range: IntRange) = Check("notes in $range") { _, notes -> notes.size in range }
    private val notARepeat = Check("not a repeat") { _, notes ->
        notes.isNotEmpty() && notes.none { NoteCheck.repeats(it.body, listOf("previous" to PREVIOUS)) != null }
    }
    private val distinctTitles = Check("distinct titles") { _, notes -> notes.map { it.title.lowercase() }.toSet().size == notes.size }
    private val spanish = Check("in Spanish") { reply, _ -> count(reply, SPANISH) > count(reply, ENGLISH) }
    private val english = Check("in English") { reply, _ -> count(reply, ENGLISH) > count(reply, SPANISH) }
    private fun shorterThan(chars: Int) = Check("under $chars chars") { reply, _ -> reply.trim().length < chars }

    private val cases = listOf(
        Case(
            "01 the first one", "explain all key points start for the first one create a note",
            listOf(noteCount(1..1), titled("open", "free", "source", "libre"), notARepeat, english),
        ),
        Case("02 the second point", "make a note about the second point", listOf(noteCount(1..1), has("kernel"), notARepeat, english)),
        Case("03 point 3 in Spanish", "haz una nota del punto 3 en español", listOf(noteCount(1..1), has("personaliz", "custom"), notARepeat, spanish)),
        Case("04 each point", "make a note for each key point", listOf(noteCount(3..5), distinctTitles, notARepeat)),
        Case("05 a topic of its own", "make a note in English about Docker", listOf(noteCount(1..1), titled("Docker"), has("container"), english)),
        Case("06 no topic: the previous answer", "make a note", listOf(noteCount(1..1), titled("Linux"))),
        Case("07 shorter", "make it shorter", listOf(noteCount(0..0), shorterThan(PREVIOUS.length / 2), has("Linux"))),
        Case("08 language switch", "ahora explícalo en español", listOf(noteCount(0..0), spanish, has("Linux"))),
    )

    @Test
    fun followUps() {
        var passed = 0
        var total = 0
        val only = InstrumentationRegistry.getArguments().getString("cases")?.split(',')?.map { it.trim() }
        for (case in cases.filter { case -> only == null || only.any { case.name.startsWith(it) } }) {
            val earlier = listOf(Assistant.Line(true, QUESTION), Assistant.Line(false, PREVIOUS))
            val memory = Assistant.remember(earlier, null, 0)
            val asNote = Assistant.wantsNote(case.message)
            val prompt = Assistant.prompt(
                case.message, emptyList(), asNote, emptyList(), memory,
                previousAnswer = if (followUp) PREVIOUS else null,
            )
            val start = System.nanoTime()
            val reply = runCatching {
                chat.reply(
                    Assistant.SYSTEM, memory.recent.map { ChatEngine.Turn(it.fromUser, it.text) }, prompt,
                    if (asNote) Assistant.NOTE_TOKENS else Assistant.ANSWER_TOKENS,
                ) {}
            }.getOrElse { "[error: ${it.message}]" }
            val seconds = (System.nanoTime() - start) / 1e9
            val notes = if (asNote) NoteFormat.parse(reply, case.message) else emptyList()
            val bad = case.checks.filterNot { it.passes(reply, notes) }.map { it.name }
            total += case.checks.size
            passed += case.checks.size - bad.size
            val stats = chat.lastStats?.let { " decode=%.1f tok/s".format(it.replyTokensPerSecond) }.orEmpty()
            if (bad.isEmpty()) {
                log("PASS ${case.name} (%.0f s$stats)".format(seconds))
            } else {
                log("FAIL ${case.name} (%.0f s$stats): ${bad.joinToString(", ")}".format(seconds))
                log("     titles=${notes.joinToString(" | ") { it.title }}")
                log("     reply=${reply.replace("\n", "⏎").take(500)}")
            }
        }
        log("RESULT checks passed $passed of $total")
    }

    private fun count(text: String, words: Regex) = words.findAll(text.lowercase()).count()

    private fun log(line: String) {
        Log.i("SEBRAIN_EVAL", line)
    }

    private companion object {
        const val QUESTION = "make a note in English about Linux"
        val PREVIOUS = """
            # What is Linux

            Linux is a family of free and open-source Unix-like operating systems based on the Linux kernel. It is highly customizable and widely used across various platforms.

            ## Key points
            - Open-source and free to use
            - Based on the Linux kernel
            - Highly customizable
            - Used in servers and embedded systems
            - Strong community support

            ## Details
            Linux is a powerful operating system that forms the foundation for many other systems. Its modular design allows users and developers to tailor the system to specific needs.

            Tags: linux, os, open-source, kernel
        """.trimIndent()
        val SPANISH = Regex("\\b(el|la|los|las|de|que|es|un|una|en|por|para|con|se|del|y)\\b")
        val ENGLISH = Regex("\\b(the|is|of|and|to|a|in|it|for|that|with|are|on)\\b")
    }
}
