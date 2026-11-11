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
import com.sebrain.core.vault.NoteStore
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class NoteDetail(val note: Note, val linksTo: List<CaptureSummary>, val linkedFrom: List<CaptureSummary>)

/** [id] is null for a new note. */
class NoteDraft(val id: Long?, val text: String)

data class ChatMessage(val fromUser: Boolean, val text: String, val isAction: Boolean = false)

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
                    app.chat.startConversation(
                        SYSTEM,
                        listOf(NoteTools(app.session, app.indexer) { action -> onMain { messages += ChatMessage(false, action, isAction = true) } }),
                    )
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
                    canAct = app.chat.supportsTools
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

    private fun generate(question: String) {
        generating = true
        messages += ChatMessage(false, "")
        val index = messages.lastIndex
        // The notes are retrieved first, on the vault thread; the model then runs on the chat thread.
        app.search(question, { db, vector ->
            val captures = CaptureStore(db)
            HybridSearch(db).search(question, vector, CONTEXT_NOTES).mapNotNull { hit ->
                captures.summaryOf(hit.captureId)?.let { "${it.title.orEmpty()}: ${hit.text}" }
            }
        }) { found ->
            val context = found.getOrDefault(emptyList())
            val prompt = if (context.isEmpty()) {
                question
            } else {
                "Notes from the vault that may be relevant:\n" +
                    context.mapIndexed { i, note -> "[${i + 1}] $note" }.joinToString("\n\n") + "\n\nQuestion: $question"
            }
            chatWorker.execute {
                val result = runCatching {
                    app.chat.send(prompt) { piece ->
                        onMain { if (index < messages.size) messages[index] = messages[index].copy(text = messages[index].text + piece) }
                    }
                }
                onMain {
                    result.onFailure {
                        if (index < messages.size) messages[index] = messages[index].copy(text = messages[index].text + "\n[error: ${it.message}]")
                    }
                    generating = false
                    refreshNotes()
                }
            }
        }
    }

    fun stopGenerating() = app.chat.cancel()

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
                "Answer directly and concisely, in the language of the question. " +
                "When notes from the vault are given, use them if they are relevant and cite them as [1], [2]. " +
                "If the notes do not contain the answer, say so and answer from your own knowledge. " +
                "Use the tools only when the user asks you to create, tag, link or search notes."
    }
}
