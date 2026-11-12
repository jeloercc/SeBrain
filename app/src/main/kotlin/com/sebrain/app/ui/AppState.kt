package com.sebrain.app.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sebrain.app.NoteTools
import com.sebrain.app.SeBrainApp
import com.sebrain.cognition.NoteIndexer
import com.sebrain.core.ml.KnownModel
import com.sebrain.core.net.HubFile
import com.sebrain.core.net.HubModel
import com.sebrain.core.net.HuggingFaceClient
import com.sebrain.core.net.TailnetGateway
import com.sebrain.core.vault.CaptureStore
import com.sebrain.core.vault.CaptureSummary
import com.sebrain.core.vault.HybridSearch
import com.sebrain.core.vault.Note
import com.sebrain.core.vault.NoteGraph
import com.sebrain.core.vault.NoteStore
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class NoteDetail(val note: Note, val linksTo: List<CaptureSummary>, val linkedFrom: List<CaptureSummary>)

/** [id] is null for a new note. */
class NoteDraft(val id: Long?, val text: String)

data class ChatMessage(val fromUser: Boolean, val text: String, val isAction: Boolean = false, val saved: Boolean = false)

/**
 * Everything the screens show and do. State is only changed on the main thread; vault, model
 * and network work runs on background threads.
 */
class AppState(private val app: SeBrainApp) {
    private val main = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) = main.post(block)

    // ---- Notes ----
    var query by mutableStateOf("")
        private set
    var selectedTag by mutableStateOf<String?>(null)
        private set
    var notes by mutableStateOf(emptyList<CaptureSummary>())
        private set
    var tags by mutableStateOf(emptyList<Pair<String, Int>>())
        private set
    var noteCount by mutableStateOf(0L)
        private set
    var vaultProblem by mutableStateOf<String?>(null)
        private set
    var detail by mutableStateOf<NoteDetail?>(null)
        private set
    var draft by mutableStateOf<NoteDraft?>(null)
    var tab by mutableStateOf(0)
    var graph by mutableStateOf<NoteGraph?>(null)
        private set

    fun loadGraph() {
        app.vault({ db -> NoteStore(db).graph() }) { result -> onMain { graph = result.getOrNull() } }
    }

    /** Opens a note from another tab. */
    fun openFromGraph(id: Long) {
        tab = 0
        open(id)
    }

    fun start() {
        app.onImported = { onMain { refreshNotes() } }
        app.requestImport()
        refreshInstalled()
    }

    /** Clears vault content from memory when the app leaves the screen. */
    fun stop() {
        app.onImported = null
        notes = emptyList()
        tags = emptyList()
        detail = null
        draft = null
        graph = null
        messages.clear()
        app.chat.cancel()
        chatWorker.execute {
            app.chat.close()
            onMain { currentModel = null; modelBackend = null }
        }
        app.releaseModels()
    }

    fun search(text: String) {
        query = text
        refreshNotes()
    }

    fun selectTag(tag: String?) {
        selectedTag = tag
        query = ""
        refreshNotes()
    }

    fun refreshNotes() {
        val wanted = query.trim()
        val tag = selectedTag
        app.search(wanted, { db, vector ->
            val captures = CaptureStore(db)
            val store = NoteStore(db)
            val found = when {
                wanted.isNotEmpty() ->
                    HybridSearch(db).search(wanted, vector, LIMIT).mapNotNull { captures.summaryOf(it.captureId) }.distinctBy { it.id }
                tag != null -> store.withTag(tag, LIMIT)
                else -> captures.recent(LIMIT)
            }
            Triple(captures.count(), found, store.tags())
        }) { result ->
            onMain {
                // A slower, older query must not replace the results of a newer one.
                if (wanted != query.trim() || tag != selectedTag) return@onMain
                result.onSuccess { (count, found, allTags) ->
                    noteCount = count
                    notes = found
                    tags = allTags
                    vaultProblem = null
                }.onFailure {
                    notes = emptyList()
                    tags = emptyList()
                    vaultProblem = "The vault is not available (${it.javaClass.simpleName})"
                }
            }
        }
    }

    fun open(id: Long) {
        app.vault({ db ->
            val store = NoteStore(db)
            store.get(id)?.let { NoteDetail(it, store.linkedFrom(id), store.backlinks(id)) }
        }) { result -> onMain { detail = result.getOrNull() } }
    }

    fun closeDetail() {
        detail = null
    }

    /** Saves the draft. [onDone] receives false if another note already has exactly this text. */
    fun save(draft: NoteDraft, text: String, onDone: (Boolean) -> Unit) {
        val hash = NoteIndexer.sha256Hex(text)
        val now = Instant.now().toString()
        app.vault({ db ->
            db.beginTransaction()
            try {
                val store = NoteStore(db)
                val id = if (draft.id == null) {
                    store.create(text, hash, now, ZoneId.systemDefault().id)
                } else {
                    draft.id.takeIf { store.updateText(it, text, hash, now) }
                }
                if (id != null) app.indexer.index(db, id, text)
                db.setTransactionSuccessful()
                id
            } finally {
                db.endTransaction()
            }
        }) { result ->
            onMain {
                val id = result.getOrNull()
                if (id != null) {
                    this.draft = null
                    refreshNotes()
                    if (detail != null) open(id)
                }
                onDone(id != null)
            }
        }
    }

    fun delete(id: Long) {
        app.vault({ db -> NoteStore(db).delete(id) }) {
            onMain {
                detail = null
                refreshNotes()
            }
        }
    }

    // ---- Chat ----
    private val chatWorker = Executors.newSingleThreadExecutor()
    private val tools = NoteTools(app.session, app.indexer) { action -> onMain { messages += ChatMessage(false, action, isAction = true) } }
    private var pendingQuestion: String? = null
    val messages = mutableStateListOf<ChatMessage>()
    var languageModels by mutableStateOf(emptyList<KnownModel>())
        private set
    var currentModel by mutableStateOf<KnownModel?>(null)
        private set
    var modelBackend by mutableStateOf<String?>(null)
        private set
    var canAct by mutableStateOf(false)
        private set
    var loadingModel by mutableStateOf(false)
        private set
    var generating by mutableStateOf(false)
        private set
    var chatProblem by mutableStateOf<String?>(null)
        private set

    /** Loads [choice], or the model used last, or the first installed one. Does nothing if it is already loaded. */
    fun ensureModel(choice: KnownModel? = null) {
        if (loadingModel || (choice == null && currentModel != null)) return
        loadingModel = true
        chatProblem = null
        chatWorker.execute {
            val result = runCatching {
                val models = app.chat.languageModels()
                val saved = app.getSharedPreferences(PREFS, 0).getString(PREF_MODEL, null)
                val model = choice ?: models.firstOrNull { it.fileName == saved } ?: models.firstOrNull()
                if (model != null) {
                    app.chat.load(model)
                    app.chat.startConversation(SYSTEM)
                    app.getSharedPreferences(PREFS, 0).edit().putString(PREF_MODEL, model.fileName).apply()
                }
                models to model
            }
            onMain {
                loadingModel = false
                result.onSuccess { (models, model) ->
                    languageModels = models
                    currentModel = model
                    modelBackend = app.chat.backend
                    canAct = model != null
                    if (model == null) chatProblem = "No language model is installed. Download one in Models."
                    pendingQuestion?.let { pendingQuestion = null; if (model != null) generate(it) }
                }.onFailure {
                    currentModel = null
                    chatProblem = "The model could not be loaded: ${it.message}"
                    pendingQuestion = null
                }
            }
        }
    }

    /** Sends a question. If the model is still loading, it is answered as soon as it is ready. */
    fun ask(question: String) {
        if (question.isBlank() || generating) return
        messages += ChatMessage(true, question.trim())
        if (currentModel == null) {
            pendingQuestion = question.trim()
            ensureModel()
        } else {
            generate(question.trim())
        }
    }

    private class Source(val title: String, val text: String)

    private fun generate(question: String) {
        generating = true
        messages += ChatMessage(false, "")
        val index = messages.lastIndex
        val asNote = wantsNote(question)
        // The notes are retrieved first, on the vault thread; the model then runs on the chat thread.
        app.search(question, { db, vector ->
            val captures = CaptureStore(db)
            HybridSearch(db).search(question, vector, CONTEXT_NOTES).mapNotNull { hit ->
                captures.summaryOf(hit.captureId)?.let { Source(it.title.orEmpty(), hit.text) }
            }
        }) { found ->
            val context = found.getOrDefault(emptyList())
            val prompt = buildString {
                if (context.isNotEmpty()) {
                    append("Notes from the vault that may be relevant:\n")
                    append(context.mapIndexed { i, note -> "[${i + 1}] ${note.title}: ${note.text}" }.joinToString("\n\n"))
                    append("\n\n")
                }
                append(if (asNote) "Request: " else "Question: ").append(question)
                if (asNote) append(NOTE_FORMAT)
            }
            chatWorker.execute {
                val result = runCatching {
                    app.chat.send(prompt) { piece ->
                        onMain { if (index < messages.size) messages[index] = messages[index].copy(text = messages[index].text + piece) }
                    }
                }
                val saved = result.getOrNull()?.takeIf { asNote }?.let { createNoteFrom(it, question, context) } == true
                onMain {
                    result.onFailure {
                        if (index < messages.size) messages[index] = messages[index].copy(text = messages[index].text + "\n[error: ${it.message}]")
                    }
                    if (saved && index < messages.size) messages[index] = messages[index].copy(saved = true)
                    generating = false
                    refreshNotes()
                }
            }
        }
    }

    fun stopGenerating() = app.chat.cancel()

    /** Saves the answer at [index] as a note. */
    fun saveAsNote(index: Int) {
        val answer = messages.getOrNull(index) ?: return
        val question = messages.take(index).lastOrNull { it.fromUser }?.text.orEmpty()
        messages[index] = answer.copy(saved = true)
        chatWorker.execute {
            createNoteFrom(answer.text, question, emptyList())
            onMain { refreshNotes() }
        }
    }

    /**
     * Stores a reply as a note in Obsidian format: `# Title`, the body, `[[links]]` to the notes
     * the reply cites, and `#tags`. Returns whether a note was created.
     */
    private fun createNoteFrom(reply: String, question: String, context: List<Source>): Boolean {
        val (title, body, tags) = parseNote(reply, question)
        if (body.isBlank()) return false
        val related = Regex("\\[(\\d+)]").findAll(reply)
            .mapNotNull { context.getOrNull(it.groupValues[1].toInt() - 1)?.title }
            .filter { it.isNotBlank() && !it.equals(title, ignoreCase = true) }.distinct().toList()
        val text = if (related.isEmpty()) body else body + "\n\nRelated: " + related.joinToString(" ") { "[[$it]]" }
        val outcome = runCatching { tools.createNote(title, text, tags) }.getOrElse { "The note could not be saved: ${it.message}" }
        val created = outcome.startsWith("Created")
        if (!created) onMain { messages += ChatMessage(false, outcome) }
        return created
    }

    // ---- Models ----
    private val gateway = TailnetGateway(app)
    private val hub = HuggingFaceClient(gateway)
    var installed by mutableStateOf(emptyList<KnownModel>())
        private set
    var semanticSearch by mutableStateOf(false)
        private set
    var hubModels by mutableStateOf<List<HubModel>?>(null)
        private set
    var hubRepo by mutableStateOf<String?>(null)
        private set
    var hubFiles by mutableStateOf(emptyList<HubFile>())
        private set
    var hubBusy by mutableStateOf(false)
        private set
    var hubMessage by mutableStateOf<String?>(null)
        private set
    var downloadProgress by mutableStateOf<Float?>(null)
        private set
    val online: Boolean get() = gateway.isAvailable
    val tailscale: Boolean get() = gateway.isTailscaleUp

    fun refreshInstalled() {
        installed = app.models.installed()
        semanticSearch = app.embedder.isAvailable
    }

    fun searchHub(words: String) = network({ hub.search(words) }) {
        hubModels = it
        hubRepo = null
        hubMessage = if (it.isEmpty()) "No repository found" else null
    }

    fun openRepo(repo: String) = network({ hub.files(repo) }) {
        hubRepo = repo
        hubFiles = it
        hubMessage = if (it.isEmpty()) "$repo has no .litertlm file" else null
    }

    fun closeRepo() {
        hubRepo = null
    }

    fun download(file: HubFile) {
        val model = KnownModel(file.fileName, file.sha256, "${file.repo.substringAfterLast('/')} · ${file.fileName}", file.repo)
        downloadProgress = 0f
        var shown = 0L
        network({
            hub.download(file, app.models.file(model)) { done, total ->
                if (done - shown > PROGRESS_STEP) {
                    shown = done
                    onMain { downloadProgress = done.toFloat() / total }
                }
            }
            app.models.trust(model)
        }, onFinally = { downloadProgress = null }) {
            hubMessage = "Installed ${model.fileName}"
            refreshInstalled()
            app.requestImport()
        }
    }

    fun importFile(uri: Uri) = network({
        app.contentResolver.openInputStream(uri)?.let { app.models.import(it) }
    }) { model ->
        hubMessage = if (model != null) "Imported ${model.description}" else "That file is not a known model"
        refreshInstalled()
        app.requestImport()
    }

    fun remove(model: KnownModel) {
        app.models.remove(model)
        refreshInstalled()
    }

    private fun <T> network(work: () -> T, onFinally: () -> Unit = {}, onSuccess: (T) -> Unit) {
        hubBusy = true
        hubMessage = null
        thread {
            val result = runCatching(work)
            onMain {
                hubBusy = false
                onFinally()
                result.onSuccess(onSuccess).onFailure { hubMessage = it.message ?: it.javaClass.simpleName }
            }
        }
    }

    private companion object {
        const val LIMIT = 100
        const val CONTEXT_NOTES = 6
        const val PROGRESS_STEP = 8L * 1024 * 1024
        const val PREFS = "sebrain"
        const val PREF_MODEL = "language_model"
        const val SYSTEM =
            "You are SeBrain, a private assistant that runs entirely on the user's phone. " +
                "Always answer the question itself, directly and completely, in the language of the question. " +
                "When notes from the vault are given, use them if they are relevant and cite them as [1], [2]. " +
                "If the notes do not contain the answer, answer from your own knowledge."
        const val NOTE_FORMAT =
            "\n\nThe answer will be saved as a note. Write the information that was asked for, complete and " +
                "useful on its own. Do not repeat the request and do not say that you are creating a note. " +
                "Use exactly this format:\n# A short title\n\nThe content, in Markdown\n\nTags: two to four lowercase one-word tags, separated by commas"

        private val NOTE_WORD = Regex("\\b(notas?|notes?)\\b", RegexOption.IGNORE_CASE)
        private val SAVE_WORD = Regex(
            "\\b(crea\\w*|haz\\w*|hace\\w*|guard\\w*|anot\\w*|escrib\\w*|gener\\w*|agreg\\w*|añad\\w*|pon\\w*|create|make|save|write|add|put)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val TAG_LINE = Regex("^[*_#\\s]*(?:tags?|etiquetas?)[*_\\s]*:(.*)$", RegexOption.IGNORE_CASE)

        /** True when the user asks for the answer to be stored as a note. */
        fun wantsNote(question: String) = NOTE_WORD.containsMatchIn(question) && SAVE_WORD.containsMatchIn(question)

        /** Splits a reply into title, body and tags. Without a `# Title` line, [fallbackTitle] is used. */
        fun parseNote(reply: String, fallbackTitle: String): Triple<String, String, List<String>> {
            val lines = reply.trim().lines().toMutableList()
            var tags = emptyList<String>()
            val tagIndex = lines.indexOfLast { TAG_LINE.matches(it.trim()) }
            if (tagIndex >= 0) {
                tags = TAG_LINE.find(lines[tagIndex].trim())!!.groupValues[1].split(',', ' ')
                    .map { it.trim().trim('*', '_', '#', '.') }.filter { it.isNotEmpty() }
                lines.removeAt(tagIndex)
            }
            val first = lines.indexOfFirst { it.isNotBlank() }
            val title = if (first >= 0 && lines[first].trimStart().startsWith("#")) {
                lines.removeAt(first).trim().trimStart('#').trim().trim('*').trim()
            } else {
                fallbackTitle.lineSequence().firstOrNull().orEmpty().take(60)
            }
            return Triple(title.ifBlank { "Note" }, lines.joinToString("\n").trim(), tags)
        }
    }
}
