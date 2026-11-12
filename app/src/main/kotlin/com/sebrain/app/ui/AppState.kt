package com.sebrain.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sebrain.app.GenerationService
import com.sebrain.app.NoteTools
import com.sebrain.app.SeBrainApp
import com.sebrain.app.chat.Assistant
import com.sebrain.app.chat.ContextNote
import com.sebrain.app.chat.NoteFormat
import com.sebrain.app.chat.VaultContext
import com.sebrain.core.ml.ChatEngine
import com.sebrain.core.vault.ConversationStore
import com.sebrain.core.vault.ConversationSummary
import com.sebrain.app.privacy.AppPrivacy
import com.sebrain.app.privacy.DexTrackerScanner
import com.sebrain.app.privacy.PrivacyAudit
import com.sebrain.cognition.NoteIndexer
import com.sebrain.cognition.MarkdownInterop
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
import com.sebrain.core.vault.VaultBackupSnapshot
import com.sebrain.core.vault.VaultSession
import com.sebrain.core.crypto.BackupMetadata
import com.sebrain.core.crypto.EncryptedBackupCodec
import com.sebrain.core.crypto.WrappedDekStore
import com.sebrain.ingest.BrainStore
import com.sebrain.ingest.MiniBrain
import java.time.Instant
import java.time.ZoneId
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.UUID
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
    var graphSource by mutableStateOf<String?>(null)
        private set
    private var graphFilterSources: Set<String>? = null
    var graphSources by mutableStateOf(emptyList<String>())
        private set
    var brains by mutableStateOf(emptyList<MiniBrain>())
        private set
    var selectedBrain by mutableStateOf<MiniBrain?>(null)
        private set
    var brainName by mutableStateOf("")
    var brainSources by mutableStateOf(emptySet<String>())
    var privacyApps by mutableStateOf(emptyList<AppPrivacy>())
        private set
    var usageAccess by mutableStateOf(false)
        private set
    /** "12 / 80" while the tracker scan runs, null otherwise. */
    var scanProgress by mutableStateOf<String?>(null)
        private set
    var backupStatus by mutableStateOf<String?>(null)
        private set

    fun loadGraph() {
        val source = graphFilterSources
        app.vault({ db ->
            val captures = CaptureStore(db)
            captures.sources() to NoteStore(db).graph(sources = source)
        }) { result ->
            onMain {
                result.onSuccess { (sources, loadedGraph) ->
                    graphSources = sources
                    graph = loadedGraph
                }
            }
        }
    }

    fun selectGraphSource(source: String?) {
        graphSource = source
        graphFilterSources = source?.let(::setOf)
        selectedBrain = null
        loadGraph()
    }

    fun selectBrain(brain: MiniBrain?) {
        selectedBrain = brain
        graphSource = brain?.sources?.singleOrNull()
        graphFilterSources = brain?.sources
        loadGraph()
    }

    fun toggleBrainSource(source: String, enabled: Boolean) {
        brainSources = brainSources.toMutableSet().apply {
            if (enabled) add(source) else remove(source)
        }.toSet()
    }

    fun saveBrain() {
        val name = brainName.trim()
        if (name.isEmpty() || brainSources.isEmpty()) return
        val updated = (brains.filterNot { it.name.equals(name, ignoreCase = true) } +
            MiniBrain(name, brainSources)).sortedBy { it.name.lowercase() }
        brains = updated
        brainName = ""
        brainSources = emptySet()
        chatWorker.execute { BrainStore(app).save(updated) }
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
        refreshPrivacy()
        loadConversations()
        started = true
        main.removeCallbacks(releaseModel)
        GenerationService.clearDone(app)
        GenerationService.onScreenOff = { stopGenerating() }
        // A reply still being written kept its messages on screen; otherwise reload them.
        unsavedReply?.let { unsavedReply = null; persist(fromUser = false, text = it) }
        if (!generating) conversationId?.let(::openConversation)
        // Load the model now, so it is ready by the time the user opens the chat.
        if (installed.any { !it.fileName.contains("embedding", ignoreCase = true) }) ensureModel()
    }

    /** Clears vault content from memory when the app leaves the screen. */
    fun stop() {
        app.onImported = null
        notes = emptyList()
        tags = emptyList()
        detail = null
        draft = null
        graph = null
        graphSources = emptyList()
        graphFilterSources = null
        brains = emptyList()
        selectedBrain = null
        conversations = emptyList()
        started = false
        // A reply in progress keeps running behind GenerationService, and its messages stay
        // until it ends. The model stays loaded for a while, so coming back does not reload it.
        if (!generating) {
            messages.clear()
            main.postDelayed(releaseModel, MODEL_IDLE_MS)
        }
        app.releaseModels()
    }

    /** True between onStart and onStop. */
    private var started = false

    /** Frees the language model after the app has been off screen for [MODEL_IDLE_MS]. */
    private val releaseModel = Runnable {
        if (started || generating) return@Runnable
        chatWorker.execute {
            app.chat.close()
            onMain {
                currentModel = null
                modelBackend = null
                if (started) ensureModel()
            }
        }
    }

    private fun markGenerating(value: Boolean) {
        if (generating == value) return
        generating = value
        if (value) GenerationService.start(app) else GenerationService.stop(app)
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

    private val privacyAudit by lazy { PrivacyAudit(app) }
    @Volatile private var scanning = false

    fun refreshPrivacy() {
        thread(name = "privacy-audit") {
            val access = privacyAudit.hasUsageAccess()
            val apps = runCatching { sortPrivacy(privacyAudit.listApps()) }.getOrElse { emptyList() }
            val savedBrains = runCatching { BrainStore(app).load() }.getOrElse { emptyList() }
            onMain {
                usageAccess = access
                if (!scanning) privacyApps = apps
                brains = savedBrains
            }
        }
        app.vault({ db -> CaptureStore(db).sources() }) { result ->
            onMain { graphSources = result.getOrDefault(emptyList()) }
        }
    }

    /** Scans every listed app's APK for trackers, one at a time, off the main thread. */
    fun scanTrackers() {
        if (scanning) return
        val apps = privacyApps
        if (apps.isEmpty()) return
        scanning = true
        scanProgress = "0 / ${apps.size}"
        thread(name = "tracker-scan", priority = Thread.MIN_PRIORITY) {
            val scanner = DexTrackerScanner()
            try {
                apps.forEachIndexed { index, target ->
                    val trackers = runCatching { privacyAudit.scanTrackers(target, scanner) }.getOrElse { emptyList() }
                    onMain {
                        privacyApps = privacyApps.map {
                            if (it.packageName == target.packageName) it.copy(trackers = trackers) else it
                        }
                        scanProgress = "${index + 1} / ${apps.size}"
                    }
                }
            } finally {
                onMain {
                    scanning = false
                    scanProgress = null
                    privacyApps = sortPrivacy(privacyApps)
                }
            }
        }
    }

    fun openUsageAccessSettings() = app.startActivity(privacyAudit.usageAccessSettings())

    fun openAppSettings(packageName: String) = app.startActivity(privacyAudit.appSettings(packageName))

    /** Most exposed first: trackers, then sensitive permissions, then data use. */
    private fun sortPrivacy(apps: List<AppPrivacy>) = apps.sortedWith(
        compareByDescending<AppPrivacy> { it.trackers?.size ?: 0 }
            .thenByDescending { it.sensitive.size }
            .thenByDescending { it.bytes ?: 0L }
            .thenBy { it.label.lowercase() },
    )

    fun exportBackup(uri: Uri, passphrase: CharArray) {
        app.background({
            try {
                val encrypted = app.session.withDatabase { db ->
                    val snapshot = VaultBackupSnapshot.export(db)
                    EncryptedBackupCodec.encrypt(
                        snapshot,
                        passphrase,
                        BackupMetadata(1, Instant.now().toString(), CaptureStore(db).count()),
                    )
                }
                app.contentResolver.openOutputStream(uri)?.use { it.write(encrypted) }
                    ?: error("Could not open backup destination")
                "Backup exported"
            } finally {
                passphrase.fill('\u0000')
            }
        }) { result ->
            onMain { backupStatus = result.fold({ it }, { "Backup failed (${it.javaClass.simpleName})" }) }
        }
    }

    fun restoreBackup(uri: Uri, passphrase: CharArray) {
        app.background({
            val restoreDir = File(app.cacheDir, "sebrain-restore-${UUID.randomUUID()}")
            var blob: ByteArray? = null
            var payload: ByteArray? = null
            try {
                val encryptedBlob = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not open backup")
                blob = encryptedBlob
                val decrypted = EncryptedBackupCodec.decrypt(encryptedBlob, passphrase)
                payload = decrypted.payload
                val session = VaultSession(
                    app,
                    File(restoreDir, "vault.db"),
                    WrappedDekStore(File(restoreDir, "dek.wrapped")),
                )
                val count = try {
                    session.withDatabase { db ->
                        val restored = VaultBackupSnapshot.restore(db, decrypted.payload)
                        val rebuilt = app.indexer.rebuildAll(db)
                        check(rebuilt.notes == restored) { "Restore index count mismatch" }
                        restored
                    }
                } finally {
                    session.close()
                }
                "Backup validated and restored to a temporary vault ($count note(s)); active vault unchanged"
            } finally {
                passphrase.fill('\u0000')
                blob?.fill(0)
                payload?.fill(0)
                restoreDir.deleteRecursively()
            }
        }) { result ->
            onMain { backupStatus = result.fold({ it }, { "Restore failed (${it.javaClass.simpleName})" }) }
        }
    }

    fun open(id: Long) {
        app.vault({ db ->
            val store = NoteStore(db)
            store.get(id)?.let { NoteDetail(it, store.linkedFrom(id), store.backlinks(id)) }
        }) { result -> onMain { detail = result.getOrNull() } }
    }

    fun openByTitle(title: String) {
        app.vault({ db -> NoteStore(db).findByTitle(title) }) { result ->
            result.getOrNull()?.let(::open)
        }
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

    fun exportMarkdown(id: Long, uri: Uri) {
        app.vault({ db -> NoteStore(db).get(id) }) { result ->
            result.onSuccess { note ->
                if (note == null) return@onSuccess
                app.background({
                    app.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(note.text.toByteArray(StandardCharsets.UTF_8))
                    } ?: error("The selected document cannot be written")
                }) {}
            }
        }
    }

    fun importMarkdown(uri: Uri) {
        app.background({
            val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: "Untitled.md"
            val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                ?: error("The selected document cannot be read")
            val document = MarkdownInterop.importDocument(name, text)
            val hash = NoteIndexer.sha256Hex(text)
            val now = Instant.now().toString()
            app.session.withDatabase { db ->
                db.beginTransaction()
                try {
                    val store = NoteStore(db)
                    val id = store.create(text, hash, now, ZoneId.systemDefault().id)
                        ?: error("A note with the same text already exists")
                    app.indexer.index(db, id, text, document.subject)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }) { result ->
            onMain {
                result.onSuccess { refreshNotes() }
                    .onFailure { vaultProblem = "Markdown import failed (${it.message ?: it.javaClass.simpleName})" }
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
    var conversations by mutableStateOf(emptyList<ConversationSummary>())
        private set

    /** The open conversation, or null for a new one that has no message yet. Kept across onStop. */
    var conversationId by mutableStateOf<Long?>(null)
        private set

    /** Written on the vault thread when a conversation is created, so later writes find it. */
    @Volatile private var storedConversation: Long? = null
    @Volatile private var stopRequested = false

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
                    if (model == null) {
                        chatProblem = "No language model is installed. Download one in Models."
                        markGenerating(false)
                    }
                    pendingQuestion?.let { pendingQuestion = null; if (model != null) generate(it) }
                }.onFailure {
                    currentModel = null
                    chatProblem = "The model could not be loaded: ${it.message}"
                    pendingQuestion = null
                    markGenerating(false)
                }
            }
        }
    }

    /** What the Chats panel is filtered by; blank lists every conversation. */
    var conversationQuery by mutableStateOf("")
        private set

    fun loadConversations() = searchConversations(conversationQuery)

    /** Lists the conversations, or with [words] the ones that have a matching message. */
    fun searchConversations(words: String) {
        conversationQuery = words
        val wanted = words.trim()
        app.vault({ db ->
            val store = ConversationStore(db)
            if (wanted.isEmpty()) store.list() else store.searchConversations(wanted)
        }) { result ->
            onMain { if (wanted == conversationQuery.trim()) conversations = result.getOrDefault(emptyList()) }
        }
    }

    /** Shows a saved conversation; the next message continues it. */
    fun openConversation(id: Long) {
        if (generating) return
        app.vault({ db ->
            val store = ConversationStore(db)
            store.messages(id) to store.summary(id)
        }) { result ->
            onMain {
                result.onSuccess { (stored, saved) ->
                    if (id != conversationId) clearSuggestions()
                    storedConversation = id
                    conversationId = id
                    summary = saved?.first
                    summarized = saved?.second ?: 0
                    messages.clear()
                    messages += stored.map { ChatMessage(it.fromUser, it.text) }
                }
            }
        }
    }

    /** The model's summary of the first [summarized] messages of the open conversation. */
    private var summary: String? = null
    private var summarized = 0
    @Volatile private var summarizing = false

    /** The stored messages of the open conversation, as the model's memory sees them. */
    private fun lines(): List<Assistant.Line> =
        messages.filter { !it.isAction && it.text.isNotBlank() }.map { Assistant.Line(it.fromUser, it.text) }

    /**
     * Summarizes the oldest part of a long conversation, after a reply and off the main thread.
     * It is cancelled if the user sends a message meanwhile, and tried again after the next reply.
     */
    private fun summarizeIfDue() {
        val conversation = storedConversation ?: return
        val all = lines()
        val covered = Assistant.summaryDue(all, summarized) ?: return
        val request = Assistant.summaryRequest(summary, all.subList(summarized, covered))
        summarizing = true
        chatWorker.execute {
            if (!summarizing) return@execute
            val text = runCatching { app.chat.reply(Assistant.SUMMARY_SYSTEM, emptyList(), request) {} }.getOrNull()?.trim()
            val finished = summarizing
            summarizing = false
            // A cancelled summary is partial text; it is dropped.
            if (!finished || text.isNullOrBlank()) return@execute
            app.vault({ db -> ConversationStore(db).setSummary(conversation, text, covered) }) { stored ->
                onMain {
                    if (stored.getOrDefault(false) && storedConversation == conversation) {
                        summary = text
                        summarized = covered
                    }
                }
            }
        }
    }

    fun newConversation() {
        if (generating) return
        storedConversation = null
        conversationId = null
        summary = null
        summarized = 0
        clearSuggestions()
        messages.clear()
    }

    fun deleteConversation(id: Long) {
        if (generating) return
        if (id == conversationId) newConversation()
        app.vault({ db -> ConversationStore(db).delete(id) }) { loadConversations() }
    }

    /** Sends a message. If the model is still loading, it is answered as soon as it is ready. */
    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty() || generating) return
        Assistant.factToRemember(text)?.let { fact ->
            rememberFact(text, fact)
            return
        }
        if (summarizing) {
            // The user comes first: drop the summary being written and answer.
            summarizing = false
            app.chat.cancel()
        }
        markGenerating(true)
        stopRequested = false
        messages += ChatMessage(true, text)
        persist(fromUser = true, text = text)
        if (currentModel == null) {
            pendingQuestion = text
            ensureModel()
        } else {
            generate(text)
        }
    }

    /**
     * "Remember that…": the fact is saved as a note tagged #memory, without asking the model.
     * Notes with that tag go into every prompt, and the user can edit or delete them in Notes.
     */
    private fun rememberFact(message: String, fact: String) {
        messages += ChatMessage(true, message)
        persist(fromUser = true, text = message)
        chatWorker.execute {
            val outcome = runCatching {
                tools.createNote("Memory: " + Assistant.shorten(fact.lineSequence().first(), 40), fact, listOf(VaultContext.MEMORY_TAG))
            }.getOrElse { "The memory could not be saved: ${it.message}" }
            val reply = if (outcome.startsWith("Created")) {
                "Remembered. It is saved as a note tagged #memory, and I will keep it in mind in every chat."
            } else {
                outcome
            }
            onMain {
                messages += ChatMessage(false, reply)
                persist(fromUser = false, text = reply)
                refreshNotes()
            }
        }
    }

    /** Facts the model proposes to remember. Nothing is saved until the user accepts one. */
    var memorySuggestions by mutableStateOf(emptyList<String>())
        private set
    var memoryNotice by mutableStateOf<String?>(null)
        private set
    private val rejectedFacts = mutableSetOf<String>()

    /** Asks the model which facts about the user in this conversation are worth keeping. */
    fun suggestMemories() {
        if (generating || currentModel == null) return
        val lines = lines()
        if (lines.none { it.fromUser }) return
        if (summarizing) {
            summarizing = false
            app.chat.cancel()
        }
        markGenerating(true)
        stopRequested = false
        memoryNotice = null
        app.vault({ db -> NoteStore(db).withTag(VaultContext.MEMORY_TAG, 50).map { it.text } }) { stored ->
            val known = stored.getOrDefault(emptyList())
            chatWorker.execute {
                val reply = runCatching {
                    if (stopRequested) "" else app.chat.reply(Assistant.FACTS_SYSTEM, emptyList(), Assistant.factsRequest(lines, known)) {}
                }.getOrDefault("")
                // A fact already inside a stored memory note is not proposed again.
                val facts = Assistant.parseFacts(reply, rejectedFacts)
                    .filterNot { fact -> known.any { it.contains(fact, ignoreCase = true) } }
                onMain {
                    markGenerating(false)
                    if (stopRequested) return@onMain
                    memorySuggestions = facts
                    memoryNotice = if (facts.isEmpty()) "Nothing new to remember in this chat." else null
                }
            }
        }
    }

    /** Saves a suggested fact as a #memory note, like "remember that…". */
    fun acceptSuggestion(fact: String) {
        memorySuggestions = memorySuggestions - fact
        chatWorker.execute {
            runCatching {
                tools.createNote("Memory: " + Assistant.shorten(fact, 40), fact, listOf(VaultContext.MEMORY_TAG))
            }
            onMain { refreshNotes() }
        }
    }

    /** Drops a suggestion; it is not proposed again in this conversation. */
    fun dismissSuggestion(fact: String) {
        rejectedFacts += fact
        memorySuggestions = memorySuggestions - fact
    }

    private fun clearSuggestions() {
        memorySuggestions = emptyList()
        memoryNotice = null
        rejectedFacts.clear()
    }

    /** Stores a message in the open conversation, creating the conversation on its first message. */
    private fun persist(fromUser: Boolean, text: String) {
        val now = Instant.now().toString()
        app.vault({ db ->
            val store = ConversationStore(db)
            val id = storedConversation
                ?: store.create(text.lineSequence().first().take(60), now).also { storedConversation = it }
            store.add(id, fromUser, text, now)
            id
        }) { result ->
            onMain {
                result.onSuccess { conversationId = it }
                    // The vault is closed while the screen is off. Keep the reply and store it on return.
                    .onFailure { if (!fromUser) unsavedReply = text }
                if (started) loadConversations()
            }
        }
    }

    /** A reply that could not be stored because the vault was closed; stored at the next start. */
    private var unsavedReply: String? = null

    private fun generate(question: String) {
        // The history is what came before this message, without the action lines.
        val earlier = messages.dropLast(1).filter { !it.isAction && it.text.isNotBlank() }
        val memory = Assistant.remember(earlier.map { Assistant.Line(it.fromUser, it.text) }, summary, summarized)
        val history = memory.recent.map { ChatEngine.Turn(it.fromUser, it.text) }
        val query = Assistant.retrievalQuery(question, earlier.lastOrNull { it.fromUser }?.text)
        messages += ChatMessage(false, "")
        val index = messages.lastIndex
        val asNote = Assistant.wantsNote(question)
        // What this conversation already wrote is not searched again, or the model copies it. For
        // a question, only what is still word for word in the history is left out, so nothing is lost.
        val exclude = if (asNote) memory.covered else memory.recent.filter { !it.fromUser }.mapNotNull { Assistant.heading(it.text) }
        // The notes are retrieved first, on the vault thread; the model then runs on the chat thread.
        val open = storedConversation
        app.search(query, { db, vector -> VaultContext.build(db, query, vector, exclude, open) }) { found ->
            val context = found.getOrNull()
            val notes = context?.notes.orEmpty()
            val prompt = Assistant.prompt(question, notes, asNote, context?.tags.orEmpty(), memory, context?.facts.orEmpty(), context?.past.orEmpty())
            chatWorker.execute {
                val result = runCatching {
                    if (stopRequested) {
                        ""
                    } else {
                        app.chat.reply(Assistant.SYSTEM, history, prompt) { piece ->
                            onMain {
                                if (index < messages.size) messages[index] = messages[index].copy(text = messages[index].text + piece)
                            }
                        }
                    }
                }
                val reply = result.getOrNull().orEmpty()
                val saved = reply.isNotBlank() && asNote && !stopRequested && createNoteFrom(reply, question, notes)
                onMain {
                    result.onFailure {
                        if (index < messages.size) {
                            messages[index] = messages[index].copy(text = messages[index].text + "\n[error: ${it.message}]")
                        }
                    }
                    // The screen may have been cleared by onStop meanwhile; the reply is still stored.
                    val visible = index < messages.size
                    val final = (if (visible) messages[index].text else reply).ifBlank { if (stopRequested) "[stopped]" else "" }
                    if (visible) messages[index] = messages[index].copy(text = final, saved = saved)
                    if (final.isNotBlank()) persist(fromUser = false, text = final)
                    markGenerating(false)
                    if (started) {
                        if (!visible) conversationId?.let(::openConversation) else if (!stopRequested) summarizeIfDue()
                        refreshNotes()
                    } else {
                        // Finished off screen: the reply is stored, so clear it from memory like onStop does.
                        messages.clear()
                        if (!stopRequested) GenerationService.notifyDone(app)
                        main.postDelayed(releaseModel, MODEL_IDLE_MS)
                    }
                }
            }
        }
    }

    fun stopGenerating() {
        stopRequested = true
        app.chat.cancel()
        // If the model was still loading, nothing will reply: free the input now.
        if (loadingModel) {
            pendingQuestion = null
            markGenerating(false)
        }
    }

    /** Saves the answer at [index] as a note. */
    fun saveAsNote(index: Int) {
        val answer = messages.getOrNull(index) ?: return
        val question = messages.take(index).lastOrNull { it.fromUser }?.text.orEmpty()
        messages[index] = answer.copy(saved = true)
        app.search(question, { db, vector -> VaultContext.build(db, question, vector) }) { found ->
            chatWorker.execute {
                createNoteFrom(answer.text, question, found.getOrNull()?.notes.orEmpty())
                onMain { refreshNotes() }
            }
        }
    }

    /**
     * Stores the notes in a reply, one per `# Title`, in Obsidian format: the title, the cleaned
     * body with each citation turned into a `[[link]]` to the cited note, only links to notes
     * that exist, and `#tags`. Returns whether at least one note was created.
     */
    private fun createNoteFrom(reply: String, question: String, context: List<ContextNote>): Boolean {
        var created = false
        for (note in NoteFormat.parse(reply, question)) {
            val text = Assistant.linkify(note.body, context, note.title)
            val outcome = runCatching { tools.createNote(note.title, text, note.tags) }
                .getOrElse { "The note could not be saved: ${it.message}" }
            if (outcome.startsWith("Created")) created = true else onMain { messages += ChatMessage(false, outcome, isAction = true) }
        }
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
        const val PROGRESS_STEP = 8L * 1024 * 1024
        const val PREFS = "sebrain"
        const val PREF_MODEL = "language_model"
        const val MODEL_IDLE_MS = 5L * 60L * 1000L
    }
}
