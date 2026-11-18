package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.VaultContext
import com.sebrain.app.eval.SearchProbe
import com.sebrain.cognition.FrontMatter
import com.sebrain.core.ml.ChatEngine
import com.sebrain.core.ml.ModelFiles
import com.sebrain.core.vault.NoteStore
import java.io.File
import kotlin.random.Random
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search quality on the real vault, with questions the phone's model writes: it picks notes at
 * random, the model writes for each one a question that the note answers, and the assistant's
 * search ([VaultContext]) must find that note. Nothing leaves the phone but numbers: no title,
 * question or note text is logged.
 *
 * The questions are written looking at the note, so they share more words with it than a
 * question from memory would. Each one logs its word overlap, and the result is also given for
 * the questions with little overlap and no title word, which are closer to a real search.
 *
 * Opt-in, and it uses the real vault: `scripts/run-synthetic-retrieval.sh <serial> [count]`.
 */
@RunWith(AndroidJUnit4::class)
class SyntheticRetrievalEval {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as SeBrainApp
    private lateinit var chat: ChatEngine

    private class Sampled(val id: Long, val title: String, val body: String)

    private class Probe(val rank: Int?, val overlap: Double, val usesTitle: Boolean)

    @After
    fun tearDown() {
        if (::chat.isInitialized) chat.close()
    }

    @Test
    fun syntheticQuestions() {
        assumeTrue("opt-in: pass -e eval synthetic", arguments.getString("eval") == "synthetic")
        val count = arguments.getString("count")?.toIntOrNull() ?: DEFAULT_COUNT
        val seed = arguments.getString("seed")?.toIntOrNull() ?: DEFAULT_SEED
        val minChars = arguments.getString("minChars")?.toIntOrNull() ?: DEFAULT_MIN_CHARS
        chat = ChatEngine(context, ModelFiles(File(context.filesDir, "models")))
        val models = chat.languageModels()
        assumeTrue("no language model installed", models.isNotEmpty())
        val wanted = arguments.getString("model") ?: PREFERRED_MODEL
        chat.load(models.firstOrNull { it.fileName.contains(wanted, ignoreCase = true) } ?: models.first())

        val notes = sample(count, seed, minChars)
        Log.i(TAG, "eval synthetic notes=${notes.size} seed=$seed minChars=$minChars embeddings=${app.embedder.isAvailable}")
        assumeTrue("no note has a title and $minChars characters of text", notes.isNotEmpty())
        val probes = notes.mapIndexedNotNull { i, note -> probe(i + 1, note) }
        report("all", probes)
        report("lowOverlap", probes.filter { it.overlap < LOW_OVERLAP })
        report("noTitleWord", probes.filter { !it.usesTitle })
        Log.i(TAG, "eval synthetic invalid=${notes.size - probes.size}")
    }

    /**
     * Notes with a title and at least [minChars] of text, picked at random with a fixed seed so a
     * run can be repeated. Also logs how many notes the vault has by length: counts only.
     */
    private fun sample(count: Int, seed: Int, minChars: Int): List<Sampled> = app.session.withDatabase { db ->
        val store = NoteStore(db)
        val all = ArrayList<Sampled>()
        val bySize = IntArray(SIZE_BOUNDS.size + 1)
        var total = 0
        var after = 0L
        while (true) {
            val page = store.page(after, PAGE)
            if (page.isEmpty()) break
            after = page.last().id
            for (note in page) {
                total++
                val body = FrontMatter.body(note.text)
                if (note.title.isBlank()) continue
                bySize[SIZE_BOUNDS.count { body.length >= it }]++
                if (body.length >= minChars) all += Sampled(note.id, note.title, body)
            }
        }
        val buckets = (listOf(0) + SIZE_BOUNDS).zip(bySize.toList()).joinToString(" ") { (from, n) -> "from$from=$n" }
        Log.i(TAG, "eval sizes notes=$total titled=${bySize.sum()} $buckets")
        all.shuffled(Random(seed)).take(count)
    }

    /** One question for [note] and where search ranks the note; null if the model gave no question. */
    private fun probe(n: Int, note: Sampled): Probe? {
        val reply = chat.reply(SearchProbe.SYSTEM, emptyList(), SearchProbe.prompt(note.title, note.body), SearchProbe.QUESTION_TOKENS) {}
        val question = SearchProbe.question(reply)
        if (question == null) {
            Log.i(TAG, "eval synthetic n=$n invalid")
            return null
        }
        val vector = if (app.embedder.isAvailable) runCatching { app.embedder.embedQuery(question) }.getOrNull() else null
        val found = app.session.withDatabase { db -> VaultContext.build(db, question, vector, message = question).notes }
        val rank = found.indexOfFirst { it.id == note.id }.takeIf { it >= 0 }?.plus(1)
        val probe = Probe(rank, SearchProbe.overlap(question, note.body), SearchProbe.usesTitle(question, note.title))
        Log.i(TAG, "eval synthetic n=$n rank=${rank ?: "miss"} overlap=%.2f usesTitle=${probe.usesTitle} read=${found.size}".format(probe.overlap))
        return probe
    }

    private fun report(group: String, probes: List<Probe>) {
        val s = SearchProbe.summarize(probes.map { it.rank })
        Log.i(
            TAG,
            "eval RESULT group=$group questions=${s.questions} recall@1=%.2f recall@5=%.2f recall@8=%.2f MRR=%.2f"
                .format(s.recallAt1, s.recallAt5, s.recallAt8, s.mrr),
        )
    }

    private companion object {
        const val TAG = "SEBRAIN_EVAL"
        const val DEFAULT_COUNT = 50
        const val DEFAULT_SEED = 7
        const val PREFERRED_MODEL = "E4B"
        const val PAGE = 200
        const val DEFAULT_MIN_CHARS = 200

        /** Lower bounds of the size buckets logged, in characters of text: 0-79, 80-199, 200-499, 500+. */
        val SIZE_BOUNDS = listOf(80, 200, 500)

        /** Below this share of words in common with the note, a question is close to one asked from memory. */
        const val LOW_OVERLAP = 0.5
    }
}
