package com.sebrain.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.sebrain.cognition.NoteIndexer
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.CaptureSummary
import com.sebrain.core.vault.HybridSearch
import com.sebrain.core.vault.Note
import com.sebrain.core.vault.NoteStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The notes: list, search, tags, links, and writing. Plain views for now; the real UI comes
 * later. The screen shows vault content, so it is excluded from screenshots.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var modelStatus: TextView
    private lateinit var importButton: Button
    private lateinit var tagBar: LinearLayout
    private lateinit var adapter: ArrayAdapter<String>
    private var shown: List<CaptureSummary> = emptyList()
    private var selectedTag: String? = null
    private val app get() = application as SeBrainApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        status = TextView(this).apply { textSize = 14f }
        modelStatus = TextView(this).apply { textSize = 12f }
        importButton = Button(this).apply {
            text = "Import embedding model"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                    REQUEST_IMPORT_MODEL,
                )
            }
        }
        val newNote = Button(this).apply {
            text = "New note"
            setOnClickListener { editNote(null) }
        }
        search = EditText(this).apply {
            hint = "Search your notes"
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        tagBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let { openNote(it.id) } }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 120, 32, 32)
                addView(status)
                addView(modelStatus)
                addView(importButton)
                addView(newNote)
                addView(search)
                addView(HorizontalScrollView(this@MainActivity).apply { addView(tagBar) })
                addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
    }

    override fun onResume() {
        super.onResume()
        app.onImported = { runOnUiThread { refresh() } }
        status.text = "Opening the vault…"
        showModelStatus()
        app.requestImport()
    }

    override fun onPause() {
        app.onImported = null
        // Vault content must not stay on screen or in memory after leaving the app.
        shown = emptyList()
        adapter.clear()
        tagBar.removeAllViews()
        app.releaseModels()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_IMPORT_MODEL || resultCode != RESULT_OK || uri == null) return
        modelStatus.text = "Checking the model file…"
        app.background({
            contentResolver.openInputStream(uri)?.let { app.models.import(it) }
        }) { result ->
            runOnUiThread {
                val model = result.getOrNull()
                modelStatus.text = if (model != null) "Imported: ${model.description}" else "That file is not a known model"
                if (model != null) {
                    importButton.visibility = View.GONE
                    // Adds vectors to the notes stored before the model was imported.
                    app.requestImport()
                }
            }
        }
    }

    private fun showModelStatus() {
        val available = app.embedder.isAvailable
        modelStatus.text = if (available) "Semantic search: on" else "Semantic search: off (no embedding model)"
        importButton.visibility = if (available) View.GONE else View.VISIBLE
    }

    private class Listing(val count: Long, val notes: List<CaptureSummary>, val tags: List<Pair<String, Int>>)

    private fun refresh() {
        val query = search.text.toString().trim()
        val tag = selectedTag
        app.search(query, { db, vector ->
            val captures = CaptureStore(db)
            val notes = NoteStore(db)
            val found = when {
                query.isNotEmpty() ->
                    HybridSearch(db).search(query, vector, LIMIT).mapNotNull { captures.summaryOf(it.captureId) }.distinctBy { it.id }
                tag != null -> notes.withTag(tag, LIMIT)
                else -> captures.recent(LIMIT)
            }
            Listing(captures.count(), found, notes.tags())
        }) { result ->
            runOnUiThread {
                // A slower, older query must not replace the results of a newer one.
                if (query != search.text.toString().trim() || tag != selectedTag) return@runOnUiThread
                result.onSuccess { listing ->
                    shown = listing.notes
                    status.text = when {
                        query.isNotEmpty() -> "${listing.notes.size} results"
                        tag != null -> "${listing.notes.size} notes with #$tag"
                        else -> "${listing.count} notes in the vault"
                    }
                    adapter.clear()
                    adapter.addAll(listing.notes.map(::row))
                    showTags(listing.tags)
                }.onFailure { error ->
                    shown = emptyList()
                    adapter.clear()
                    tagBar.removeAllViews()
                    status.text = "The vault is not available (${error.javaClass.simpleName})"
                }
            }
        }
    }

    private fun row(note: CaptureSummary): String {
        val title = note.title ?: note.text.lineSequence().firstOrNull().orEmpty()
        val preview = note.text.replace('\n', ' ').take(PREVIEW)
        return "$title\n${date(note.capturedAt)} · $preview"
    }

    private fun showTags(tags: List<Pair<String, Int>>) {
        tagBar.removeAllViews()
        if (selectedTag != null) {
            tagBar.addView(tagButton("× all notes") { selectedTag = null; refresh() })
        }
        for ((name, count) in tags.take(MAX_TAGS)) {
            tagBar.addView(tagButton("#$name $count") { selectedTag = name; search.setText(""); refresh() })
        }
    }

    private fun tagButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setOnClickListener { onClick() }
    }

    private class NoteView(val note: Note, val backlinks: List<CaptureSummary>, val linked: List<CaptureSummary>)

    private fun openNote(id: Long) {
        app.vault({ db ->
            val store = NoteStore(db)
            store.get(id)?.let { NoteView(it, store.backlinks(id), store.linkedFrom(id)) }
        }) { result ->
            runOnUiThread {
                val view = result.getOrNull() ?: return@runOnUiThread
                val note = view.note
                val body = buildString {
                    append(note.text)
                    if (note.tags.isNotEmpty()) append("\n\nTags: ").append(note.tags.joinToString(" ") { "#$it" })
                    if (view.linked.isNotEmpty()) append("\n\nLinks to: ").append(view.linked.joinToString(", ") { it.title.orEmpty() })
                    if (view.backlinks.isNotEmpty()) append("\n\nLinked from: ").append(view.backlinks.joinToString(", ") { it.title.orEmpty() })
                }
                val related = (view.linked + view.backlinks).distinctBy { it.id }
                val builder = AlertDialog.Builder(this)
                    .setTitle(note.title.ifEmpty { date(note.capturedAt) })
                    .setMessage("${date(note.capturedAt)} · ${note.source}\n\n$body")
                    .setPositiveButton("Edit") { _, _ -> editNote(note) }
                    .setNegativeButton("Close", null)
                    .setNeutralButton("More") { _, _ -> noteActions(note, related) }
                secure(builder.show())
            }
        }
    }

    private fun noteActions(note: Note, related: List<CaptureSummary>) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels += "Share to another app"
        actions += { confirmShare(note) }
        for (other in related) {
            labels += "Open: ${other.title.orEmpty()}"
            actions += { openNote(other.id) }
        }
        labels += "Delete note"
        actions += { confirmDelete(note) }
        secure(
            AlertDialog.Builder(this).setTitle(note.title)
                .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
                .setNegativeButton("Cancel", null)
                .show(),
        )
    }

    /** Sharing sends the note's plain text out of the vault, so it is always confirmed. */
    private fun confirmShare(note: Note) {
        secure(
            AlertDialog.Builder(this)
                .setTitle("Share this note?")
                .setMessage("Its text will leave the vault, unencrypted, to the app you choose.")
                .setPositiveButton("Share") { _, _ ->
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, note.title)
                                .putExtra(Intent.EXTRA_TEXT, note.text),
                            null,
                        ),
                    )
                }
                .setNegativeButton("Cancel", null)
                .show(),
        )
    }

    private fun confirmDelete(note: Note) {
        secure(
            AlertDialog.Builder(this)
                .setTitle("Delete this note?")
                .setMessage("It cannot be recovered.")
                .setPositiveButton("Delete") { _, _ ->
                    app.vault({ db -> NoteStore(db).delete(note.id) }) { runOnUiThread { refresh() } }
                }
                .setNegativeButton("Cancel", null)
                .show(),
        )
    }

    /** Creates a note, or edits [existing]. Tags (#tag) and links ([[Title]]) are read from the text. */
    private fun editNote(existing: Note?) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 8
            hint = "First line is the title.\nUse #tags and [[links to other notes]]."
            setText(existing?.text.orEmpty())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New note" else "Edit note")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .show()
        secure(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                input.error = "Write something first"
                return@setOnClickListener
            }
            save(existing, text) { saved ->
                if (saved) {
                    dialog.dismiss()
                    refresh()
                } else {
                    input.error = "Another note already has exactly this text"
                }
            }
        }
    }

    private fun save(existing: Note?, text: String, onDone: (Boolean) -> Unit) {
        val hash = NoteIndexer.sha256Hex(text)
        val now = Instant.now().toString()
        app.vault({ db ->
            db.beginTransaction()
            try {
                val store = NoteStore(db)
                val id = if (existing == null) {
                    store.create(text, hash, now, ZoneId.systemDefault().id)
                } else {
                    existing.id.takeIf { store.updateText(it, text, hash, now) }
                }
                if (id != null) app.indexer.index(db, id, text)
                db.setTransactionSuccessful()
                id != null
            } finally {
                db.endTransaction()
            }
        }) { result -> runOnUiThread { onDone(result.getOrDefault(false)) } }
    }

    private fun secure(dialog: AlertDialog) {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun date(capturedAt: String): String =
        runCatching { FORMAT.format(Instant.parse(capturedAt).atZone(ZoneId.systemDefault())) }.getOrDefault(capturedAt)

    private companion object {
        const val LIMIT = 50
        const val PREVIEW = 120
        const val MAX_TAGS = 30
        const val REQUEST_IMPORT_MODEL = 1
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")
    }
}
