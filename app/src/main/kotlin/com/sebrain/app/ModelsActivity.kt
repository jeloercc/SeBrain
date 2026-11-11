package com.sebrain.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.sebrain.core.ml.KnownModel
import com.sebrain.core.net.HubFile
import com.sebrain.core.net.HubModel
import com.sebrain.core.net.HuggingFaceClient
import com.sebrain.core.net.TailnetGateway
import kotlin.concurrent.thread

/**
 * Finds LiteRT-LM models on Hugging Face and installs the ones the user confirms.
 * It uses the device's connection, through Tailscale when Tailscale is on.
 */
class ModelsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var query: EditText
    private lateinit var adapter: ArrayAdapter<String>
    private var onItem: (Int) -> Unit = {}
    private val app get() = application as SeBrainApp
    private val gateway by lazy { TailnetGateway(this) }
    private val hub by lazy { HuggingFaceClient(gateway) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 14f }
        query = EditText(this).apply {
            hint = "Search models, for example: gemma uncensored"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> search(); true }
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 120, 32, 32)
                addView(status)
                addView(query)
                addView(Button(this@ModelsActivity).apply { text = "Search Hugging Face"; setOnClickListener { search() } })
                addView(Button(this@ModelsActivity).apply { text = "Installed models"; setOnClickListener { showInstalled() } })
                addView(
                    ListView(this@ModelsActivity).apply {
                        adapter = this@ModelsActivity.adapter
                        setOnItemClickListener { _, _, position, _ -> onItem(position) }
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
        showInstalled()
    }

    private fun show(rows: List<String>, onClick: (Int) -> Unit) {
        adapter.clear()
        adapter.addAll(rows)
        onItem = onClick
    }

    private fun showInstalled() {
        val installed = app.models.installed()
        status.text = when {
            !gateway.isAvailable -> "No internet connection"
            gateway.isTailscaleUp -> "Internet: on, with Tailscale active"
            else -> "Internet: on"
        }
        show(installed.map { "${it.description}\n${it.fileName}" }) { position ->
            val model = installed[position]
            AlertDialog.Builder(this)
                .setTitle(model.description)
                .setMessage("${model.fileName}\n${model.repo ?: "built in"}\nSHA-256 ${model.sha256}")
                .setPositiveButton("Close", null)
                .setNegativeButton("Remove") { _, _ -> app.models.remove(model); showInstalled() }
                .show()
        }
    }

    private fun search() {
        val words = query.text.toString().trim()
        status.text = "Searching…"
        network({ hub.search(words) }) { models: List<HubModel> ->
            status.text = "${models.size} repositories"
            show(models.map { "${it.repo}\n${it.downloads} downloads · ${it.likes} likes${if (it.gated) " · needs an account" else ""}" }) {
                showFiles(models[it].repo)
            }
        }
    }

    private fun showFiles(repo: String) {
        status.text = "Reading $repo…"
        network({ hub.files(repo) }) { files: List<HubFile> ->
            status.text = if (files.isEmpty()) "$repo has no .litertlm file" else repo
            show(files.map { "${it.fileName}\n${"%.2f".format(it.sizeBytes / 1e9)} GB" }) { confirm(files[it]) }
        }
    }

    /** The user decides to trust a model after seeing where it comes from and its hash. */
    private fun confirm(file: HubFile) {
        AlertDialog.Builder(this)
            .setTitle("Download and trust this model?")
            .setMessage(
                "${file.repo}\n${file.fileName}\n${"%.2f".format(file.sizeBytes / 1e9)} GB\n\nSHA-256 ${file.sha256}\n\n" +
                    "It is a file from a third party. SeBrain will check the download against this hash.",
            )
            .setPositiveButton("Download") { _, _ -> download(file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun download(file: HubFile) {
        val model = KnownModel(file.fileName, file.sha256, "${file.repo.substringAfterLast('/')} (${file.fileName})", file.repo)
        var lastShown = 0L
        network({
            hub.download(file, app.models.file(model)) { done, total ->
                if (done - lastShown > PROGRESS_STEP) {
                    lastShown = done
                    runOnUiThread { status.text = "Downloading ${"%.0f".format(100.0 * done / total)} %" }
                }
            }
            app.models.trust(model)
        }) {
            status.text = "Installed ${model.fileName}"
            showInstalled()
        }
    }

    /** Runs network work off the main thread and reports failures in the status line. */
    private fun <T> network(work: () -> T, onSuccess: (T) -> Unit) {
        thread {
            val result = runCatching(work)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess(onSuccess).onFailure { status.text = it.message ?: it.javaClass.simpleName }
            }
        }
    }

    private companion object {
        const val PROGRESS_STEP = 16L * 1024 * 1024
    }
}
