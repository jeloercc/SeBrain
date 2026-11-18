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
import com.sebrain.app.chat.NoteEdits
import com.sebrain.app.chat.NoteFormat
import com.sebrain.app.chat.Pins
import com.sebrain.app.chat.VaultContext
import com.sebrain.core.ml.ChatEngine
import com.sebrain.core.vault.Brain
import com.sebrain.core.vault.BrainRegistry
import com.sebrain.core.vault.ConversationStore
import com.sebrain.core.vault.ConversationSummary
import com.sebrain.app.privacy.AppPrivacy
import com.sebrain.app.privacy.DexTrackerScanner
import com.sebrain.app.privacy.PrivacyAudit
import com.sebrain.cognition.NoteIndexer
import com.sebrain.cognition.FrontMatter
import com.sebrain.cognition.MarkdownInterop
import com.sebrain.cognition.NoteMarkup
import com.sebrain.cognition.NoteSections
import com.sebrain.app.VaultFolder
import com.sebrain.app.ProviderStore
import com.sebrain.core.net.RemoteChat
import com.sebrain.core.net.RemoteMessage
import com.sebrain.core.net.RemoteProvider
import com.sebrain.core.vault.CaptureRecord
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
import com.sebrain.core.crypto.BackupMetadata
import com.sebrain.core.crypto.EncryptedBackupCodec
import com.sebrain.app.VaultRestore
import com.sebrain.ingest.BrainStore
import com.sebrain.ingest.ShareActivity
import com.sebrain.ingest.MiniBrain
import java.time.Instant
import java.time.ZoneId
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class NoteDetail(
    val note: Note,
    val linksTo: List<CaptureSummary>,
    val linkedFrom: List<CaptureSummary>,
    /** Notes that name this one without linking to it. */
    val mentions: List<CaptureSummary> = emptyList(),
    /** The text of each note or section this note embeds, by [NoteSections.embedKey]. */
    val embeds: Map<String, String> = emptyMap(),
    /** Why the note's properties could not be read in full, if they could not. */
    val problem: String? = null,
)

/** [id] is null for a new note. */
class NoteDraft(val id: Long?, val text: String)

data class ChatMessage(val fromUser: Boolean, val text: String, val isAction: Boolean = false, val saved: Boolean = false)

/**
 * Everything the screens show and do. State is only changed on the main thread; vault, model
 * and network work runs on background threads.
 */
class AppState(private val app: SeBrainApp) {
    private val main = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        main.post(block)
    }

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
            captures.sources() to notes(db).graph(sources = source)
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
        reindexIfNeeded()
        loadProviders()
        loadBrains()
        app.requestImport()
        migratePins()
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
        folders = emptyList()
        proposalQueue.clear()
        proposalsLeft = 0
        proposal = null
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
        selectedFolder = null
        query = ""
        refreshNotes()
    }

    /** Folders that hold notes, with how many each holds, subfolders included. */
    var folders by mutableStateOf(emptyList<Pair<String, Int>>())
        private set
    var selectedFolder by mutableStateOf<String?>(null)
        private set

    fun selectFolder(folder: String?) {
        selectedFolder = folder
        selectedTag = null
        query = ""
        refreshNotes()
    }

    /** Set on the vault thread by [refreshNotes], read on the main thread right after. */
    @Volatile private var loadedFolders = emptyList<Pair<String, Int>>()

    fun refreshNotes() {
        val wanted = query.trim()
        val tag = selectedTag
        val folder = selectedFolder
        app.search(wanted, { db, vector ->
            val captures = CaptureStore(db)
            val store = notes(db)
            val found = when {
                wanted.isNotEmpty() ->
                    HybridSearch(db, scopeBrain).search(wanted, vector, LIMIT).mapNotNull { captures.summaryOf(it.captureId) }.distinctBy { it.id }
                tag != null -> store.withTag(tag, LIMIT)
                folder != null -> store.inFolder(folder, LIMIT)
                else -> store.recent(LIMIT)
            }
            loadedFolders = store.folders()
            Triple(store.count(), found, store.tags())
        }) { result ->
            onMain {
                // A slower, older query must not replace the results of a newer one.
                if (wanted != query.trim() || tag != selectedTag || folder != selectedFolder) return@onMain
                result.onSuccess { (count, found, allTags) ->
                    noteCount = count
                    notes = found
                    tags = allTags
                    folders = loadedFolders
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
        hasPreviousVault = vaultRestore.hasPrevious
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

    private val vaultRestore by lazy {
        VaultRestore(app, app.session, app.vaultSwap, app.indexer) { ShareActivity.forgetDuplicates(app) }
    }

    /** A checked backup waiting for the user to replace the vault with it: counts only. */
    var pendingRestore by mutableStateOf<VaultRestore.Pending?>(null)
        private set

    /** Whether the vault from before the last restore is kept, so the restore can be undone. */
    var hasPreviousVault by mutableStateOf(false)
        private set

    /** Restores the backup into a new vault next to the active one and checks it; the active vault is unchanged. */
    fun restoreBackup(uri: Uri, passphrase: CharArray) {
        app.background({
            try {
                val encrypted = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not open backup")
                try {
                    vaultRestore.stage(encrypted, passphrase)
                } finally {
                    encrypted.fill(0)
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }) { result ->
            onMain {
                pendingRestore = result.getOrNull()
                backupStatus = result.fold(
                    { p ->
                        "Backup checked: ${p.notes} note(s), ${p.conversations} conversation(s), ${p.brains} brain(s). " +
                            "The vault is unchanged until you replace it." +
                            if (p.brainsMadePrivate > 0) " This backup did not save which brains are private, so all ${p.brainsMadePrivate} will be." else ""
                    },
                    { "Restore failed (${it.javaClass.simpleName})" },
                )
            }
        }
    }

    fun replaceVault() {
        app.background({ vaultRestore.replace() }) { result ->
            onMain { afterSwap(result, "Vault replaced. The previous one is kept: UNDO RESTORE brings it back.") }
        }
    }

    fun undoRestore() {
        app.background({ vaultRestore.undo() }) { result -> onMain { afterSwap(result, "The previous vault is back.") } }
    }

    fun discardRestore() {
        app.background({ vaultRestore.discard() }) {
            onMain {
                pendingRestore = null
                backupStatus = "Restore discarded; the vault is unchanged"
            }
        }
    }

    fun deletePreviousVault() {
        app.background({ vaultRestore.deletePrevious() }) { result ->
            onMain {
                hasPreviousVault = vaultRestore.hasPrevious
                backupStatus = result.fold({ "Previous vault deleted" }, { "Could not delete the previous vault (${it.javaClass.simpleName})" })
            }
        }
    }

    private fun afterSwap(result: Result<Unit>, done: String) {
        pendingRestore = null
        hasPreviousVault = vaultRestore.hasPrevious
        backupStatus = result.fold({ done }, { "Could not swap the vault (${it.javaClass.simpleName}); try again" })
        if (result.isSuccess) {
            refreshNotes()
            loadBrains()
            // Captures waiting in the inbox go into the vault that is now active.
            app.requestImport()
        }
    }

    fun open(id: Long) {
        app.vault({ db ->
            val store = notes(db)
            store.get(id)?.let { note ->
                val parsed = NoteMarkup.parse(note.text)
                // An embedded note is read here, off the main thread, and only one level deep.
                val embeds = parsed.linkDetails.filter { it.embed }.mapNotNull { link ->
                    val text = store.findByTitle(link.target)?.let(store::get)?.let { NoteSections.extract(it.text, link.anchor) }
                    text?.let { NoteSections.embedKey(link.target, link.anchor) to Assistant.shorten(it, EMBED_CHARS) }
                }.toMap()
                NoteDetail(note, store.linkedFrom(id), store.backlinks(id), store.unlinkedMentions(id), embeds, parsed.problem)
            }
        }) { result -> onMain { detail = result.getOrNull() } }
    }

    /** Turns the first mention of the open note inside the note [sourceId] into a link to it. */
    fun linkMention(sourceId: Long) {
        val target = detail?.note ?: return
        val now = Instant.now().toString()
        app.vault({ db ->
            val store = NoteStore(db)
            val source = store.get(sourceId) ?: return@vault false
            val text = NoteSections.linkMention(source.text, target.title, listOf(target.title) + target.aliases) ?: return@vault false
            db.beginTransaction()
            try {
                val changed = store.updateText(sourceId, text, NoteIndexer.sha256Hex(text), now)
                if (changed) app.indexer.index(db, sourceId, text)
                db.setTransactionSuccessful()
                changed
            } finally {
                db.endTransaction()
            }
        }) { onMain { open(target.id) } }
    }

    fun openByTitle(title: String) {
        app.vault({ db -> notes(db).findByTitle(title) }) { result ->
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
                val store = notes(db)
                val id = if (draft.id == null) {
                    store.create(text, hash, now, ZoneId.systemDefault().id)?.also { placeInBrain(store, it, text) }
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

    /** Pins or unpins a note: the assistant reads pinned notes in every prompt. */
    fun togglePinned(id: Long) {
        val now = Instant.now().toString()
        app.vault({ db ->
            val store = NoteStore(db)
            val note = store.get(id) ?: return@vault false
            val text = if (Pins.isPinned(note.text)) Pins.unpin(note.text) else Pins.pin(note.text)
            db.beginTransaction()
            try {
                val changed = store.updateText(id, text, NoteIndexer.sha256Hex(text), now)
                if (changed) app.indexer.index(db, id, text)
                db.setTransactionSuccessful()
                changed
            } finally {
                db.endTransaction()
            }
        }) { result ->
            onMain {
                if (result.getOrDefault(false)) {
                    refreshNotes()
                    if (detail?.note?.id == id) open(id)
                }
            }
        }
    }

    /**
     * Notes tagged #memory before 2026-11-12 become pinned notes. Runs once, when the vault
     * can be opened; a failure leaves the flag unset so it is tried again at the next start.
     */
    /**
     * Indexes every note again when the shape of the stored chunks has changed (see
     * [NoteIndexer.INDEX_VERSION]). The notes are only marked here; the import that follows
     * indexes them in the background, and search by words keeps working meanwhile.
     */
    private fun reindexIfNeeded() {
        val prefs = app.getSharedPreferences(PREFS, 0)
        if (prefs.getInt(PREF_INDEX_VERSION, 1) >= NoteIndexer.INDEX_VERSION) return
        app.vault({ db -> CaptureStore(db).markAllPending() }) { result ->
            result.onSuccess {
                prefs.edit().putInt(PREF_INDEX_VERSION, NoteIndexer.INDEX_VERSION).apply()
                app.requestImport()
            }
        }
    }

    private fun migratePins() {
        val prefs = app.getSharedPreferences(PREFS, 0)
        if (prefs.getBoolean(PREF_PINS_MIGRATED, false)) return
        val now = Instant.now().toString()
        app.vault({ db ->
            val store = NoteStore(db)
            db.beginTransaction()
            try {
                var changed = 0
                for (summary in store.withTag(Pins.LEGACY_TAG, LIMIT)) {
                    val text = Pins.migrate(summary.text) ?: continue
                    if (store.updateText(summary.id, text, NoteIndexer.sha256Hex(text), now)) {
                        app.indexer.index(db, summary.id, text)
                        changed++
                    }
                }
                db.setTransactionSuccessful()
                changed
            } finally {
                db.endTransaction()
            }
        }) { result ->
            result.onSuccess {
                prefs.edit().putBoolean(PREF_PINS_MIGRATED, true).apply()
                if (it > 0) onMain { refreshNotes() }
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
                    val store = notes(db)
                    val id = store.create(text, hash, now, ZoneId.systemDefault().id)
                        ?: error("A note with the same text already exists")
                    scopeBrain?.let { store.setPath(id, store.freePath(it, document.subject)) }
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

    /** What a vault import or export is doing, or its report when it ends. */
    var vaultTransfer by mutableStateOf<String?>(null)
        private set
    @Volatile private var transferring = false

    /** Runs [block] on the vault thread and waits for it, so a long job can go in small steps. */
    private fun <T> vaultStep(block: (net.zetetic.database.sqlcipher.SQLiteDatabase) -> T): Result<T> {
        val done = java.util.concurrent.CountDownLatch(1)
        var outcome: Result<T>? = null
        app.vault(block) { outcome = it; done.countDown() }
        done.await()
        return outcome!!
    }

    /**
     * Imports an Obsidian vault folder: every Markdown file becomes a note with its path, its
     * properties, tags and links. A file whose path is already in the vault updates that note.
     * It runs in small batches so search and the chat keep working meanwhile.
     */
    fun importVault(tree: Uri) {
        if (transferring) return
        transferring = true
        vaultTransfer = "Reading the folder…"
        thread(name = "vault-import") {
            var created = 0; var updated = 0; var unchanged = 0; var unreadable = 0; var problems = 0
            val report = runCatching {
                val folder = VaultFolder(app.contentResolver, tree)
                val listing = folder.list()
                val zone = ZoneId.systemDefault().id
                listing.notes.chunked(IMPORT_BATCH).forEachIndexed { batch, files ->
                    onMain { vaultTransfer = "Importing ${batch * IMPORT_BATCH} of ${listing.notes.size} notes…" }
                    val texts = files.map { it to folder.read(it) }
                    unreadable += texts.count { it.second == null }
                    val now = Instant.now().toString()
                    vaultStep { db ->
                        db.beginTransaction()
                        try {
                            val store = NoteStore(db)
                            for ((file, text) in texts) {
                                if (text == null) continue
                                val path = MarkdownInterop.notePath(file.relative) ?: continue
                                val subject = path.substringAfterLast('/')
                                if (NoteMarkup.parse(text).problem != null) problems++
                                val existing = store.idAtPath(path)
                                if (existing != null) {
                                    if (store.get(existing)?.text == text) {
                                        unchanged++
                                    } else if (store.updateText(existing, text, NoteIndexer.sha256Hex(text), now)) {
                                        app.indexer.index(db, existing, text, subject)
                                        updated++
                                    }
                                    continue
                                }
                                val id = CaptureStore(db).insertReturningId(
                                    CaptureRecord(UUID.randomUUID(), NoteStore.contentHash(text, path), now, zone, NoteStore.SOURCE_VAULT, subject, text),
                                ) ?: continue
                                store.setPath(id, path)
                                app.indexer.index(db, id, text, subject)
                                created++
                            }
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }.getOrThrow()
                }
                buildString {
                    append("Imported $created notes")
                    if (updated > 0) append(", updated $updated")
                    if (unchanged > 0) append(", $unchanged unchanged")
                    if (unreadable > 0) append(" · $unreadable could not be read or are over 2 MB")
                    if (listing.ignored > 0) append(" · ${listing.ignored} other files ignored (attachments are not imported)")
                    if (problems > 0) append(" · $problems with properties that could not be read in full")
                }
            }.getOrElse { "Import stopped after $created notes: ${it.message ?: it.javaClass.simpleName}" }
            transferring = false
            onMain {
                vaultTransfer = report
                refreshNotes()
                loadGraph()
            }
        }
    }

    /** Writes every note as a Markdown file under [tree], in its folder. The files are plain text. */
    fun exportVault(tree: Uri) {
        if (transferring) return
        transferring = true
        vaultTransfer = "Exporting…"
        thread(name = "vault-export") {
            var written = 0; var failed = 0
            val report = runCatching {
                val folder = VaultFolder(app.contentResolver, tree)
                val used = HashSet<String>()
                var after = 0L
                while (true) {
                    val notes = vaultStep { db -> NoteStore(db).page(after, IMPORT_BATCH) }.getOrThrow()
                    if (notes.isEmpty()) break
                    after = notes.last().id
                    for (note in notes) {
                        var parts = MarkdownInterop.exportFile(note.path, note.title)
                        // Two notes without a path can share a title; the second gets a number.
                        var n = 2
                        while (!used.add(parts.joinToString("/").lowercase())) {
                            parts = parts.dropLast(1) + (parts.last().removeSuffix(".md") + " ($n).md")
                            n++
                        }
                        if (folder.write(parts, note.text)) written++ else failed++
                    }
                    onMain { vaultTransfer = "Exported $written notes…" }
                }
                "Exported $written notes as plain Markdown" + if (failed > 0) " · $failed could not be written" else ""
            }.getOrElse { "Export stopped after $written notes: ${it.message ?: it.javaClass.simpleName}" }
            transferring = false
            onMain { vaultTransfer = report }
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
            val store = chats(db)
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
                    // Earlier turns with an external model belong to the conversation they were in.
                    if (id != storedConversation) remoteHistory.clear()
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
            val text = runCatching { app.chat.reply(Assistant.SUMMARY_SYSTEM, emptyList(), request, Assistant.SUMMARY_TOKENS) {} }.getOrNull()?.trim()
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
        remoteHistory.clear()
        summary = null
        summarized = 0
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
        if (summarizing) {
            // The user comes first: drop the summary being written and answer.
            summarizing = false
            app.chat.cancel()
        }
        messages += ChatMessage(true, text)
        persist(fromUser = true, text = text)
        // "Revisa conceptos no enlazados en todas nuestras notas": a review of the whole vault.
        if (NoteEdits.wantsVaultReview(text)) {
            reviewVaultLinks(fromChat = true)
            return
        }
        // "Corrige la nota X": a tool on an existing note, if the message names one.
        val tool = NoteEdits.kindOf(text)
        if (tool != null) {
            app.vault({ db -> NoteEdits.mentioned(text, notes(db).names().map { it.first to it.third }) }) { found ->
                onMain {
                    val id = found.getOrNull()
                    if (id != null) proposeEdit(id, tool, text, fromChat = true) else answer(text)
                }
            }
            return
        }
        answer(text)
    }

    /** Answers a message that is already in the conversation. */
    private fun answer(text: String) {
        if (generating) return
        remoteProvider?.let { provider ->
            prepareRemote(provider, text)
            return
        }
        markGenerating(true)
        stopRequested = false
        if (currentModel == null) {
            pendingQuestion = text
            ensureModel()
        } else {
            generate(text)
        }
    }

    /** Models that write the long system instruction back; they get the compact one. Kept across starts. */
    private fun compactModels(): Set<String> = app.getSharedPreferences(PREFS, 0).getStringSet(PREF_COMPACT, emptySet()).orEmpty()

    private fun rememberCompact(model: String) {
        if (model.isEmpty()) return
        val prefs = app.getSharedPreferences(PREFS, 0)
        prefs.edit().putStringSet(PREF_COMPACT, compactModels() + model).apply()
    }

    /**
     * How fast the loaded model writes, in tokens per second, taken from its last replies. It
     * starts at the speed measured for the first model and follows whatever model is loaded.
     */
    @Volatile private var writingSpeed = 7.5

    /** Runs a model call and updates [writingSpeed] from how long its reply took. */
    private fun timed(call: () -> String): String {
        val start = System.nanoTime()
        val reply = call()
        val seconds = (System.nanoTime() - start) / 1e9
        val tokens = reply.length / 3.6
        // Short replies are mostly waiting for the first token; they say little about speed.
        if (tokens >= 20 && seconds > 1) writingSpeed = (writingSpeed + tokens / seconds) / 2
        return reply
    }

    // ---- Tools on an existing note ----

    /** A change to a note that waits for the user: nothing is written until it is applied. */
    class NoteProposal(
        val noteId: Long,
        val title: String,
        val kind: NoteEdits.Kind,
        val before: String,
        val after: String,
        val warnings: List<String>,
        val changes: List<Pair<NoteEdits.Change, String>>,
    )

    var proposal by mutableStateOf<NoteProposal?>(null)
        private set

    /** What a tool is doing, or why it produced nothing. */
    var editStatus by mutableStateOf<String?>(null)
        private set

    /**
     * Runs a tool on a note and shows its result as a proposal. Linking uses retrieval and no
     * language model; the others make one model call, short for a fix or tags, as long as the
     * note for a rewrite.
     */
    fun proposeEdit(noteId: Long, kind: NoteEdits.Kind, instruction: String = "", fromChat: Boolean = false) {
        if (generating) return
        fun say(status: String) {
            editStatus = status
            if (fromChat) messages += ChatMessage(false, status, isAction = true)
        }
        if (kind.usesModel && currentModel == null) return say("The model is still loading. Try again in a moment.")
        app.vault({ db -> NoteStore(db).get(noteId) }) { loaded ->
            val note = loaded.getOrNull() ?: return@vault onMain { say("That note could not be opened.") }
            val body = FrontMatter.body(note.text)
            if (kind == NoteEdits.Kind.LINK) return@vault proposeLinks(note, body, ::say)
            val limit = if (kind == NoteEdits.Kind.IMPROVE || kind == NoteEdits.Kind.EDIT) NoteEdits.MAX_CHARS else NoteEdits.MAX_CHARS * 2
            if (body.length > limit) {
                return@vault onMain { say("\"${note.title}\" is too long for the phone model to ${kind.label.lowercase()} (${body.length} characters, limit $limit).") }
            }
            val tags = if (kind == NoteEdits.Kind.TAGS) runCatching { app.session.withDatabase { notes(it).tags().take(40).map { t -> t.first } } }.getOrDefault(emptyList()) else emptyList()
            onMain {
                if (generating) return@onMain
                if (summarizing) {
                    summarizing = false
                    app.chat.cancel()
                }
                markGenerating(true)
                stopRequested = false
                val addition = kind == NoteEdits.Kind.EDIT && NoteEdits.isAddition(instruction)
                say("${kind.label}: \"${note.title}\" · about ${NoteEdits.secondsFor(kind, body.length, writingSpeed, addition)} s")
                val system = when (kind) {
                    NoteEdits.Kind.FIX -> NoteEdits.FIX_SYSTEM
                    NoteEdits.Kind.TAGS -> NoteEdits.TAGS_SYSTEM
                    else -> NoteEdits.SYSTEM
                }
                val prompt = NoteEdits.prompt(kind, if (kind == NoteEdits.Kind.TAGS) Assistant.shorten(body, 3000) else body, instruction, tags)
                chatWorker.execute {
                    val limit = if (addition) NoteEdits.ADDITION_TOKENS else NoteEdits.maxTokens(kind, body.length)
                    val reply = runCatching { timed { app.chat.reply(system, emptyList(), prompt, limit) {} } }
                    val result = reply.getOrNull()?.takeIf { !stopRequested }?.let {
                        when (kind) {
                            NoteEdits.Kind.FIX -> NoteEdits.corrected(note.text, it)
                            NoteEdits.Kind.TAGS -> NoteEdits.tagged(note.text, it)
                            else -> if (addition) NoteEdits.appended(note.text, it) else NoteEdits.rewritten(kind, note.text, it)
                        }
                    }
                    onMain {
                        markGenerating(false)
                        when {
                            reply.isFailure -> say("The model failed: ${reply.exceptionOrNull()?.message}")
                            stopRequested -> say("Stopped.")
                            else -> offer(note, kind, result, ::say)
                        }
                    }
                }
            }
        }
    }

    /** Proposals still waiting behind the one on screen, from a review of the whole vault. */
    private val proposalQueue = ArrayDeque<NoteProposal>()
    var proposalsLeft by mutableStateOf(0)
        private set

    /**
     * Looks in every note for other notes it names without linking them, and queues one proposal
     * per note to review. It also reports the links that point to notes that do not exist. No
     * language model is used, so it takes seconds and works with any model loaded, or none.
     */
    fun reviewVaultLinks(fromChat: Boolean = false) {
        if (transferring || generating) return
        transferring = true
        fun say(status: String) {
            editStatus = status
            // An action line, not an answer: it is shown but never given back to the model as
            // something it said, which misled it about what the conversation was about.
            if (fromChat) messages += ChatMessage(false, status, isAction = true)
        }
        editStatus = "Reviewing the links of every note…"
        thread(name = "vault-links") {
            val found = ArrayList<NoteProposal>()
            var links = 0
            var reviewed = 0
            val outcome = runCatching {
                val names = vaultStep { db -> notes(db).names() }.getOrThrow()
                val byNote = names.groupBy({ it.first }, { it.second to it.third })
                val all = byNote.map { (id, pairs) -> id to (pairs.first().first to pairs.map { it.second }) }
                val index = NoteEdits.Names(all.map { it.second })
                val titleOf = all.associate { it.first to it.second.first }
                var after = 0L
                while (found.size < REVIEW_MAX) {
                    val notes = vaultStep { db -> notes(db).page(after, IMPORT_BATCH) }.getOrThrow()
                    if (notes.isEmpty()) break
                    after = notes.last().id
                    for (note in notes) {
                        reviewed++
                        val own = titleOf[note.id]
                        val candidates = index.candidatesFor(FrontMatter.body(note.text)).filter { it.first != own }
                        val result = NoteEdits.linked(note.text, candidates) ?: continue
                        val before = NoteMarkup.parse(note.text).links.size
                        links += NoteMarkup.parse(result.text).links.size - before
                        found += NoteProposal(note.id, note.title, NoteEdits.Kind.LINK, note.text, result.text, result.warnings, NoteEdits.diff(note.text, result.text))
                        if (found.size == REVIEW_MAX) break
                    }
                }
                vaultStep { db -> notes(db).unresolvedLinks() }.getOrDefault(emptyList())
            }
            transferring = false
            onMain {
                val missing = outcome.getOrDefault(emptyList())
                val report = buildString {
                    if (outcome.isFailure) {
                        append("The review stopped: ${outcome.exceptionOrNull()?.message ?: "the vault is not available"}.")
                    } else if (found.isEmpty()) {
                        append("Reviewed $reviewed notes: every note that names another one already links to it.")
                    } else {
                        append("Reviewed $reviewed notes: $links mention(s) without a link in ${found.size} note(s). Review them one by one; nothing changes until you apply.")
                        if (found.size == REVIEW_MAX) append(" Only the first $REVIEW_MAX notes are queued; run it again after these.")
                    }
                    if (missing.isNotEmpty()) {
                        append("\n\nLinked but not written yet: ")
                        append(missing.joinToString(", ") { (title, count) -> if (count > 1) "$title ($count notes)" else title })
                        append('.')
                    }
                }
                say(report)
                proposalQueue.clear()
                proposalQueue.addAll(found)
                nextProposal()
            }
        }
    }

    private fun nextProposal() {
        proposal = proposalQueue.removeFirstOrNull()
        proposalsLeft = proposalQueue.size
    }

    /** Ends a review of the vault, leaving the notes not yet reviewed as they are. */
    fun closeReview() {
        proposalQueue.clear()
        proposalsLeft = 0
        proposal = null
    }

    /** Links by name, then related notes by retrieval: the note's own start is the search query. */
    private fun proposeLinks(note: Note, body: String, say: (String) -> Unit) {
        val query = note.title + "\n" + Assistant.shorten(body, 400)
        app.search(query, { db, vector ->
            val store = notes(db)
            val others = store.names().filter { it.first != note.id }.groupBy({ it.second }, { it.third }).map { it.key to it.value }
            // Close in meaning: found by the vector search, near its top. Without an embedding model, none.
            val related = if (vector == null) emptyList() else HybridSearch(db, scopeBrain).search(query, vector, 12)
                .filter { it.captureId != note.id && (it.vectorRank ?: Int.MAX_VALUE) <= RELATED_RANK }
                .mapNotNull { CaptureStore(db).summaryOf(it.captureId)?.title }.distinct()
            NoteEdits.linked(note.text, others, related)
        }) { result -> onMain { offer(note, NoteEdits.Kind.LINK, result.getOrNull(), say) } }
    }

    private fun offer(note: Note, kind: NoteEdits.Kind, result: NoteEdits.Result?, say: (String) -> Unit) {
        if (result == null || result.text.trimEnd() == note.text.trimEnd()) return say("${kind.label}: nothing to change in \"${note.title}\".")
        proposal = NoteProposal(note.id, note.title, kind, note.text, result.text, result.warnings, NoteEdits.diff(note.text, result.text))
        say("${kind.label}: a change to \"${note.title}\" is ready to review.")
    }

    /** Writes the proposal, unless the note changed since it was made. */
    fun applyProposal() {
        val change = proposal ?: return
        val now = Instant.now().toString()
        app.vault({ db ->
            val store = NoteStore(db)
            if (store.get(change.noteId)?.text != change.before) return@vault false
            db.beginTransaction()
            try {
                val changed = store.updateText(change.noteId, change.after, NoteIndexer.sha256Hex(change.after), now)
                if (changed) app.indexer.index(db, change.noteId, change.after)
                db.setTransactionSuccessful()
                changed
            } finally {
                db.endTransaction()
            }
        }) { result ->
            onMain {
                nextProposal()
                editStatus = if (result.getOrDefault(false)) {
                    "${change.kind.label}: applied to \"${change.title}\"."
                } else {
                    "Not applied: \"${change.title}\" changed meanwhile. Run the tool again."
                }
                refreshNotes()
                if (detail?.note?.id == change.noteId) open(change.noteId)
            }
        }
    }

    /** Leaves the note as it is, and moves to the next one if a review is under way. */
    fun discardProposal() {
        nextProposal()
        if (proposal == null) editStatus = null
    }

    /** Stores a message in the open conversation, creating the conversation on its first message. */
    private fun persist(fromUser: Boolean, text: String) {
        val now = Instant.now().toString()
        app.vault({ db ->
            val store = chats(db)
            val id = storedConversation
                ?: store.create(text.lineSequence().first().take(60), now).also { storedConversation = it }
            // (store is the current brain's: a conversation belongs to the brain it was started in)
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
        app.search(query, { db, vector -> VaultContext.build(db, query, vector, exclude, open, question, scopeBrain, brainId) }) { found ->
            val context = found.getOrNull()
            val notes = context?.notes.orEmpty()
            val prompt = Assistant.prompt(
                question, notes, asNote, context?.tags.orEmpty(), memory, context?.pinned.orEmpty(), context?.past.orEmpty(),
                previousAnswer = earlier.lastOrNull { !it.fromUser }?.text,
            )
            val model = currentModel?.fileName.orEmpty()
            chatWorker.execute {
                val limit = if (asNote) Assistant.NOTE_TOKENS else Assistant.ANSWER_TOKENS
                var echoed = false

                /** One reply. Its first characters are held back until they are known not to be the instructions. */
                fun attempt(system: String): String {
                    val streamed = StringBuilder()
                    var checked = false
                    return timed {
                        app.chat.reply(system, history, prompt, limit) { piece ->
                            streamed.append(piece)
                            val show = if (checked) piece else if (streamed.length >= Assistant.ECHO_CHARS) {
                                checked = true
                                if (Assistant.echoes(streamed)) {
                                    echoed = true
                                    app.chat.cancel()
                                }
                                streamed.toString()
                            } else {
                                ""
                            }
                            if (!echoed && show.isNotEmpty()) {
                                onMain { if (index < messages.size) messages[index] = messages[index].copy(text = messages[index].text + show) }
                            }
                        }
                    }
                }
                var looped = false
                val result = runCatching {
                    if (stopRequested) {
                        ""
                    } else {
                        val compact = model in compactModels()
                        var text = attempt(if (compact) Assistant.SYSTEM_COMPACT else Assistant.SYSTEM)
                        if (echoed && !stopRequested && !compact) {
                            // This model copies the long instructions. Use the short ones, now and from here on.
                            rememberCompact(model)
                            echoed = false
                            text = attempt(Assistant.SYSTEM_COMPACT)
                        }
                        looped = app.chat.lastLooped
                        if (echoed || Assistant.echoes(text)) "" else text
                    }
                }
                val reply = result.getOrNull().orEmpty()
                val saved = reply.isNotBlank() && asNote && !stopRequested && !looped && createNoteFrom(reply, question, notes)
                onMain {
                    // The screen may have been cleared by onStop meanwhile; the reply is still stored.
                    val visible = index < messages.size
                    // The returned reply is what counts: it is the text after any loop was cut.
                    val body = when {
                        result.isFailure -> (if (visible) messages[index].text else "") + "\n[error: ${result.exceptionOrNull()?.message}]"
                        echoed -> Assistant.ECHO_NOTICE
                        looped -> reply.trimEnd() + "\n\n" + Assistant.LOOP_NOTICE
                        else -> Assistant.dropPreamble(reply)
                    }
                    val final = Assistant.dropUnknownCitations(body, notes.size).ifBlank { if (stopRequested) "[stopped]" else "" }
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
        remoteChat.cancel()
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
    private fun createNoteFrom(reply: String, question: String, context: List<ContextNote>, source: String = "assistant"): Boolean {
        var created = false
        for (note in NoteFormat.parse(reply, question)) {
            val text = Assistant.linkify(note.body, context, note.title)
            val outcome = runCatching { tools.createNoteFrom(note.title, text, note.tags, source, scopeBrain, note.aliases) }
                .getOrElse { "The note could not be saved: ${it.message}" }
            if (outcome.startsWith("Created")) created = true else onMain { messages += ChatMessage(false, outcome, isAction = true) }
        }
        return created
    }

    // ---- Brains ----

    /** The sub-brains: top-level folders, each seen on its own. */
    var subBrains by mutableStateOf(emptyList<Brain>())
        private set

    /** The brain everything is scoped to, or null for the universal brain, which sees every note. */
    var currentBrain by mutableStateOf<Brain?>(null)
        private set

    private val scopeBrain: String? get() = currentBrain?.name
    private val brainId: Long? get() = currentBrain?.id

    /** The notes of the current brain. Every read of the vault for the screens goes through it. */
    private fun notes(db: net.zetetic.database.sqlcipher.SQLiteDatabase) = NoteStore(db, scopeBrain)

    private fun chats(db: net.zetetic.database.sqlcipher.SQLiteDatabase) = ConversationStore(db, brainId)

    fun loadBrains() {
        val chosen = app.getSharedPreferences(PREFS, 0).getString(PREF_BRAIN, null)
        app.vault({ db -> BrainRegistry(db).list(Instant.now().toString()) }) { result ->
            onMain {
                subBrains = result.getOrDefault(emptyList())
                val current = subBrains.firstOrNull { it.name.equals(currentBrain?.name ?: chosen, ignoreCase = true) }
                if (current?.id != currentBrain?.id || current?.private != currentBrain?.private) {
                    currentBrain = current
                    rescope()
                }
            }
        }
    }

    /** Switches every screen to [brain]; null is the universal brain. */
    fun switchBrain(brain: Brain?) {
        if (generating || brain?.id == currentBrain?.id) return
        currentBrain = brain
        app.getSharedPreferences(PREFS, 0).edit().apply { if (brain == null) remove(PREF_BRAIN) else putString(PREF_BRAIN, brain.name) }.apply()
        // An external model cannot be used in a private brain.
        if (brain?.private == true && remoteProvider != null) useLocalModel()
        rescope()
    }

    private fun rescope() {
        selectedTag = null
        selectedFolder = null
        query = ""
        detail = null
        attachedNotes = emptyList()
        newConversation()
        refreshNotes()
        loadGraph()
        loadConversations()
    }

    fun createBrain(name: String, private: Boolean) {
        app.vault({ db -> BrainRegistry(db).create(name, private, Instant.now().toString()) }) { result ->
            onMain {
                result.onSuccess { brain ->
                    loadBrains()
                    switchBrain(brain)
                }.onFailure { vaultProblem = it.message }
            }
        }
    }

    fun setBrainPrivate(brain: Brain, private: Boolean) {
        app.vault({ db -> BrainRegistry(db).setPrivate(brain.id, private, Instant.now().toString()) }) {
            onMain {
                if (private && currentBrain?.id == brain.id) {
                    currentBrain = brain.copy(private = true)
                    if (remoteProvider != null) useLocalModel()
                }
                loadBrains()
            }
        }
    }

    /** Moves a note into [brain]'s folder, or out of every brain with null. */
    fun moveNote(id: Long, brain: Brain?) {
        app.vault({ db ->
            val store = NoteStore(db)
            val note = store.get(id) ?: return@vault false
            val path = brain?.let { store.freePath(it.name, note.title) }
            db.beginTransaction()
            try {
                val moved = store.setPath(id, path)
                db.setTransactionSuccessful()
                moved
            } finally {
                db.endTransaction()
            }
        }) { result ->
            onMain {
                if (!result.getOrDefault(false)) vaultProblem = "The note could not be moved"
                detail = null
                loadBrains()
                refreshNotes()
            }
        }
    }

    /** A new note written while a sub-brain is open goes into its folder. */
    private fun placeInBrain(store: NoteStore, id: Long, text: String) {
        val brain = scopeBrain ?: return
        store.setPath(id, store.freePath(brain, NoteMarkup.titleOf(text)))
    }

    // ---- External AI over an API ----

    private val providerStore by lazy { ProviderStore(app) }
    private val remoteChat by lazy { RemoteChat(gateway) }

    /** The providers the user added. Their keys stay in [ProviderStore]. */
    var providers by mutableStateOf(emptyList<RemoteProvider>())
        private set

    /** The external model answering in the chat, or null for the model on the phone. */
    var remoteProvider by mutableStateOf<RemoteProvider?>(null)
        private set

    /** Notes the user attached to the next message for the external model, as (id, title). */
    var attachedNotes by mutableStateOf(emptyList<Pair<Long, String>>())
        private set

    /** What is about to leave the phone, waiting for the user to confirm it. */
    class OutgoingRequest(
        val provider: RemoteProvider,
        val question: String,
        val messages: List<RemoteMessage>,
        val notes: List<ContextNote>,
        val asNote: Boolean,
    ) {
        val characters: Int get() = messages.sumOf { it.content.length }
        val text: String get() = messages.joinToString("\n\n") { "[${it.role}]\n${it.content}" }
    }

    var outgoing by mutableStateOf<OutgoingRequest?>(null)
        private set

    var providerProblem by mutableStateOf<String?>(null)
        private set

    /** What this conversation said to and heard from external models; nothing local is in it. */
    private val remoteHistory = ArrayList<RemoteMessage>()

    fun loadProviders() {
        thread(name = "providers") {
            val list = runCatching { providerStore.list() }.getOrDefault(emptyList())
            val chosen = app.getSharedPreferences(PREFS, 0).getString(PREF_ENGINE, null)
            onMain {
                providers = list
                remoteProvider = list.firstOrNull { it.id == chosen }
            }
        }
    }

    fun saveProvider(provider: RemoteProvider, apiKey: String) {
        thread(name = "providers") {
            val result = runCatching { providerStore.save(provider, apiKey) }
            onMain {
                providerProblem = result.exceptionOrNull()?.message
                if (result.isSuccess) loadProviders()
            }
        }
    }

    fun removeProvider(id: String) {
        thread(name = "providers") {
            runCatching { providerStore.remove(id) }
            if (app.getSharedPreferences(PREFS, 0).getString(PREF_ENGINE, null) == id) useLocalModel()
            loadProviders()
        }
    }

    /** Answers with an external model from now on. Notes stay on the phone unless attached. */
    fun useProvider(provider: RemoteProvider) {
        if (currentBrain?.private == true) return
        remoteProvider = provider
        app.getSharedPreferences(PREFS, 0).edit().putString(PREF_ENGINE, provider.id).apply()
    }

    fun useLocalModel() {
        onMain {
            remoteProvider = null
            attachedNotes = emptyList()
        }
        app.getSharedPreferences(PREFS, 0).edit().remove(PREF_ENGINE).apply()
    }

    fun attachNote(id: Long, title: String) {
        if (attachedNotes.none { it.first == id }) attachedNotes = attachedNotes + (id to title)
    }

    fun detachNote(id: Long) {
        attachedNotes = attachedNotes.filterNot { it.first == id }
    }

    /** Notes whose title or text matches [words], to choose what to attach. */
    var attachCandidates by mutableStateOf(emptyList<Pair<Long, String>>())
        private set

    fun searchAttachable(words: String) {
        val wanted = words.trim()
        app.vault({ db ->
            val captures = CaptureStore(db)
            val store = notes(db)
            val private = BrainRegistry(db).privateNames()
            val found = if (wanted.isEmpty()) store.recent(40) else HybridSearch(db, scopeBrain).search(wanted, null, 40).mapNotNull { captures.summaryOf(it.captureId) }
            // A note of a private brain is never offered to an external model.
            found.filter { BrainRegistry.of(store.pathOf(it.id))?.lowercase() !in private }.take(20)
        }) { result ->
            onMain { attachCandidates = result.getOrDefault(emptyList()).distinctBy { it.id }.map { it.id to (it.title ?: it.text.take(40)) } }
        }
    }

    /**
     * Builds what will be sent: the instruction, this conversation's earlier turns with external
     * models, the attached notes, and the message. It is shown before anything leaves.
     */
    private fun prepareRemote(provider: RemoteProvider, question: String) {
        val attached = attachedNotes
        if (currentBrain?.private == true) {
            messages += ChatMessage(false, "This brain is private: only the phone's model can be used in it.", isAction = true)
            return
        }
        app.vault({ db ->
            val store = NoteStore(db)
            val private = BrainRegistry(db).privateNames()
            attached.mapIndexedNotNull { i, (id, _) ->
                // Checked again here, at the last moment, whatever the screen offered.
                store.get(id)?.takeIf { BrainRegistry.of(it.path)?.lowercase() !in private }?.let { ContextNote(i + 1, it.id, it.title, it.tags, emptyList(), emptyList(), FrontMatter.body(it.text), focus = true) }
            }
        }) { found ->
            onMain {
                val notes = found.getOrDefault(emptyList())
                val asNote = Assistant.wantsNote(question)
                val previous = remoteHistory.lastOrNull { it.role == "assistant" }?.content
                val prompt = Assistant.prompt(question, notes, asNote, emptyList(), previousAnswer = previous)
                val messages = listOf(RemoteMessage("system", Assistant.REMOTE_SYSTEM)) +
                    remoteHistory.takeLast(REMOTE_HISTORY) + RemoteMessage("user", prompt)
                outgoing = OutgoingRequest(provider, question, messages, notes, asNote)
            }
        }
    }

    /** The user saw what leaves and agreed: send it. */
    fun confirmOutgoing() {
        val request = outgoing ?: return
        outgoing = null
        val key = runCatching { providerStore.apiKey(request.provider.id) }.getOrNull()
        if (key.isNullOrEmpty()) {
            messages += ChatMessage(false, "No API key is stored for ${request.provider.name}.", isAction = true)
            return
        }
        markGenerating(true)
        stopRequested = false
        attachedNotes = emptyList()
        messages += ChatMessage(false, "")
        val index = messages.lastIndex
        val limit = if (request.asNote) REMOTE_NOTE_TOKENS else REMOTE_ANSWER_TOKENS
        thread(name = "remote-chat") {
            val result = runCatching {
                remoteChat.reply(request.provider, key, request.messages, limit) { piece ->
                    onMain { if (index < messages.size) messages[index] = messages[index].copy(text = messages[index].text + piece) }
                }
            }
            val reply = result.getOrNull().orEmpty()
            val source = "${request.provider.name} · ${request.provider.model}"
            val saved = reply.isNotBlank() && request.asNote && !stopRequested && createNoteFrom(reply, request.question, request.notes, source)
            onMain {
                val visible = index < messages.size
                val body = when {
                    result.isFailure -> "[${request.provider.name}: ${result.exceptionOrNull()?.message ?: "the request failed"}]"
                    else -> Assistant.dropPreamble(reply)
                }
                val final = Assistant.dropUnknownCitations(body, request.notes.size).ifBlank { if (stopRequested) "[stopped]" else "" }
                if (visible) messages[index] = messages[index].copy(text = final, saved = saved)
                if (final.isNotBlank()) persist(fromUser = false, text = final)
                if (result.isSuccess && reply.isNotBlank()) {
                    remoteHistory += RemoteMessage("user", request.question)
                    remoteHistory += RemoteMessage("assistant", reply)
                }
                markGenerating(false)
                refreshNotes()
            }
        }
    }

    /** Nothing was sent. The message stays in the conversation without an answer. */
    fun cancelOutgoing() {
        outgoing = null
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
        const val PREF_PINS_MIGRATED = "pins_migrated"
        const val PREF_COMPACT = "compact_prompt_models"
        const val PREF_INDEX_VERSION = "index_version"
        const val PREF_ENGINE = "chat_engine"
        const val PREF_BRAIN = "brain"
        const val REMOTE_HISTORY = 8
        const val REMOTE_ANSWER_TOKENS = 2000
        const val REMOTE_NOTE_TOKENS = 4000
        const val IMPORT_BATCH = 20
        const val EMBED_CHARS = 1500
        const val RELATED_RANK = 5
        const val REVIEW_MAX = 40
        const val MODEL_IDLE_MS = 5L * 60L * 1000L
    }
}
