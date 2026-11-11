package com.sebrain.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sebrain.core.ml.KnownModel
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.HybridSearch
import java.util.concurrent.Executors

/**
 * Chat with the local language model. Each question is answered with the most relevant notes
 * as context, and the model may act on notes through [NoteTools]. Nothing leaves the device.
 */
class ChatActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private val app get() = application as SeBrainApp
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        status = TextView(this).apply { textSize = 12f }
        transcript = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(transcript) }
        input = EditText(this).apply { hint = "Ask about your notes, or ask to create one"; maxLines = 4 }
        send = Button(this).apply { text = "Send"; setOnClickListener { onSend() } }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 120, 32, 32)
                addView(status)
                addView(Button(this@ChatActivity).apply { text = "Choose model"; setOnClickListener { chooseModel() } })
                addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(input)
                addView(send)
            },
        )
    }

    override fun onStart() {
        super.onStart()
        loadModel(null)
    }

    override fun onStop() {
        // The model uses gigabytes of memory, and the transcript is vault content.
        app.chat.cancel()
        worker.execute { app.chat.close() }
        transcript.text = ""
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun chooseModel() {
        worker.execute {
            val models = app.chat.languageModels()
            runOnUiThread {
                if (models.isEmpty()) {
                    noModel()
                } else {
                    AlertDialog.Builder(this).setTitle("Language model")
                        .setItems(models.map { it.description }.toTypedArray()) { _, which ->
                            getPreferences(MODE_PRIVATE).edit().putString(PREF_MODEL, models[which].fileName).apply()
                            loadModel(models[which])
                        }.show()
                }
            }
        }
    }

    private fun noModel() {
        status.text = "No language model is installed."
        AlertDialog.Builder(this)
            .setMessage("Download a language model first.")
            .setPositiveButton("Open Models") { _, _ -> startActivity(Intent(this, ModelsActivity::class.java)) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Loads [choice], or the model used last time, or the first one installed. */
    private fun loadModel(choice: KnownModel?) {
        setBusy(true, "Loading the model…")
        worker.execute {
            val result = runCatching {
                val models = app.chat.languageModels()
                val saved = getPreferences(MODE_PRIVATE).getString(PREF_MODEL, null)
                val model = choice ?: models.firstOrNull { it.fileName == saved } ?: models.firstOrNull()
                if (model != null) {
                    app.chat.load(model)
                    app.chat.startConversation(
                        SYSTEM,
                        listOf(NoteTools(app.session, app.indexer) { action -> runOnUiThread { append("\n✓ $action\n") } }),
                    )
                }
                model
            }
            runOnUiThread {
                result.onSuccess { model ->
                    if (model == null) {
                        setBusy(true, "")
                        noModel()
                    } else {
                        val tools = if (app.chat.supportsTools) "can act on notes" else "answers only"
                        setBusy(false, "${model.description} · ${app.chat.backend} · $tools")
                    }
                }.onFailure { setBusy(true, "The model could not be loaded: ${it.message}") }
            }
        }
    }

    private fun onSend() {
        val question = input.text.toString().trim()
        if (question.isEmpty() || busy) return
        input.setText("")
        append("\nYou: $question\n\nSeBrain: ")
        setBusy(true, status.text.toString())
        // The notes are retrieved first, on the vault thread; the model then runs on this screen's thread.
        app.search(question, { db, vector ->
            val captures = CaptureStore(db)
            HybridSearch(db).search(question, vector, CONTEXT_NOTES).mapNotNull { hit ->
                captures.summaryOf(hit.captureId)?.let { "${it.title.orEmpty()}: ${hit.text}" }
            }
        }) { found ->
            val notes = found.getOrDefault(emptyList())
            val prompt = if (notes.isEmpty()) {
                question
            } else {
                "Notes from the vault that may be relevant:\n" +
                    notes.mapIndexed { i, note -> "[${i + 1}] $note" }.joinToString("\n\n") +
                    "\n\nQuestion: $question"
            }
            worker.execute {
                val result = runCatching { app.chat.send(prompt) { piece -> runOnUiThread { append(piece) } } }
                runOnUiThread {
                    result.onFailure { append("\n[error: ${it.message}]") }
                    append("\n")
                    setBusy(false, status.text.toString())
                }
            }
        }
    }

    private fun append(text: String) {
        transcript.append(text)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setBusy(isBusy: Boolean, message: String) {
        busy = isBusy
        send.isEnabled = !isBusy
        status.text = message
    }

    private companion object {
        const val PREF_MODEL = "language_model"
        const val CONTEXT_NOTES = 6
        const val SYSTEM =
            "You are SeBrain, a private assistant that runs entirely on the user's phone. " +
                "Answer directly and concisely, in the language of the question. " +
                "When notes from the vault are given, use them if they are relevant and cite them as [1], [2]. " +
                "If the notes do not contain the answer, say so and answer from your own knowledge. " +
                "Use the tools only when the user asks you to create, tag, link or search notes."
    }
}
