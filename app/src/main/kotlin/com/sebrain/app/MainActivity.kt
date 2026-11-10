package com.sebrain.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.CaptureSummary
import com.sebrain.core.vault.HybridSearch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Lists the captures in the vault and searches them by text. Plain views for now; the real UI
 * comes later. The screen shows vault content, so it is excluded from screenshots.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var adapter: ArrayAdapter<String>
    private var shown: List<CaptureSummary> = emptyList()
    private val app get() = application as SeBrainApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        status = TextView(this).apply { textSize = 14f }
        search = EditText(this).apply {
            hint = "Search your captures"
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let(::showCapture) }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 120, 32, 32)
                addView(status)
                addView(search)
                addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
    }

    override fun onResume() {
        super.onResume()
        app.onImported = { runOnUiThread { refresh() } }
        status.text = "Opening the vault…"
        app.requestImport()
    }

    override fun onPause() {
        app.onImported = null
        // Vault content must not stay on screen or in memory after leaving the app.
        shown = emptyList()
        adapter.clear()
        super.onPause()
    }

    private fun refresh() {
        val query = search.text.toString().trim()
        app.readVault({ db ->
            val store = CaptureStore(db)
            val captures = if (query.isEmpty()) {
                store.recent(LIMIT)
            } else {
                HybridSearch(db).search(query, null, LIMIT).mapNotNull { store.summaryOf(it.captureId) }.distinctBy { it.id }
            }
            store.count() to captures
        }) { result ->
            runOnUiThread {
                // A slower, older query must not replace the results of a newer one.
                if (query != search.text.toString().trim()) return@runOnUiThread
                result.onSuccess { (count, captures) ->
                    shown = captures
                    status.text = if (query.isEmpty()) "$count captures in the vault" else "${captures.size} results"
                    adapter.clear()
                    adapter.addAll(captures.map { "${date(it.capturedAt)} · ${it.text.replace('\n', ' ').take(PREVIEW)}" })
                }.onFailure { error ->
                    shown = emptyList()
                    adapter.clear()
                    status.text = "The vault is not available (${error.javaClass.simpleName})"
                }
            }
        }
    }

    private fun showCapture(capture: CaptureSummary) {
        AlertDialog.Builder(this)
            .setTitle("${date(capture.capturedAt)} · ${capture.source}")
            .setMessage(capture.text)
            .setPositiveButton("Close", null)
            .show()
            .window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun date(capturedAt: String): String =
        runCatching { FORMAT.format(Instant.parse(capturedAt).atZone(ZoneId.systemDefault())) }.getOrDefault(capturedAt)

    private companion object {
        const val LIMIT = 50
        const val PREVIEW = 140
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")
    }
}
