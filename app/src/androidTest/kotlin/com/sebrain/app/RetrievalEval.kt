package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.app.chat.VaultContext
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Retrieval quality on the real vault, measured the standard way: recall@k (is the expected
 * note among the first k the assistant reads?) and MRR (how high is it?). It reads a golden
 * set, `files/eval/golden.csv` with columns `id,question,expected_title`, put there by
 * `scripts/eval-retrieval.sh`. Only numbers and question ids are logged, never note text.
 * Run it by name; it is not part of any suite.
 */
@RunWith(AndroidJUnit4::class)
class RetrievalEval {
    @Test
    fun goldenSet() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SeBrainApp
        val file = File(app.filesDir, "eval/golden.csv")
        assumeTrue("no golden set at ${file.path}", file.exists())
        val rows = parse(file.readText()).filter { it.size >= 3 && it[0] != "id" }
        assumeTrue("the golden set is empty", rows.isNotEmpty())
        val ranks = ArrayList<Int?>()
        for (row in rows) {
            val (id, question, expected) = Triple(row[0], row[1], row[2].trim().lowercase())
            val vector = if (app.embedder.isAvailable) runCatching { app.embedder.embedQuery(question) }.getOrNull() else null
            val notes = app.session.withDatabase { db -> VaultContext.build(db, question, vector, message = question).notes }
            val rank = notes.indexOfFirst { it.title.trim().lowercase() == expected }.takeIf { it >= 0 }?.plus(1)
            ranks += rank
            Log.i(TAG, "eval question=$id rank=${rank ?: "miss"} read=${notes.size}")
        }
        fun recall(k: Int) = ranks.count { it != null && it <= k }.toDouble() / ranks.size
        val mrr = ranks.sumOf { if (it == null) 0.0 else 1.0 / it } / ranks.size
        Log.i(
            TAG,
            "eval RESULT questions=${ranks.size} recall@1=%.2f recall@5=%.2f recall@8=%.2f MRR=%.2f embeddings=${app.embedder.isAvailable}"
                .format(recall(1), recall(5), recall(8), mrr),
        )
    }

    /** CSV with quoted fields, as spreadsheets write it. */
    private fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> { row += field.toString(); field.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
                    row = ArrayList(); field.clear()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows
    }

    private companion object {
        const val TAG = "SEBRAIN_EVAL"
    }
}
