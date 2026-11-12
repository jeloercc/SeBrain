package com.sebrain.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebrain.core.vault.CaptureSummary
import com.sebrain.core.vault.NoteGraph
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

internal fun formatDate(capturedAt: String): String =
    runCatching { DATE.format(Instant.parse(capturedAt).atZone(ZoneId.systemDefault())) }.getOrDefault(capturedAt)

@Composable
fun NotesScreen(state: AppState, modifier: Modifier = Modifier) {
    val draft = state.draft
    val detail = state.detail
    when {
        draft != null -> NoteEditor(state, draft)
        detail != null -> NoteDetailScreen(state, detail)
        else -> NoteList(state, modifier)
    }
}

@Composable
private fun NoteList(state: AppState, modifier: Modifier) {
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(state::importMarkdown)
    }
    LaunchedEffect(Unit) { state.loadGraph() }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SeBrainMark(Modifier.size(48.dp))
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("SeBrain", style = MaterialTheme.typography.titleLarge, color = Neon.Cyan)
                    Text("01. NOTES / LOCAL VAULT", style = MaterialTheme.typography.labelSmall, color = Neon.Dim)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("VAULT / SQLCIPHER", style = MaterialTheme.typography.labelSmall, color = Neon.Dim)
                    Text(
                        if (state.currentModel != null) "LOCAL AI: READY ●" else "LOCAL AI: NOT LOADED",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.currentModel != null) Neon.Coral else Neon.Dim,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { importPicker.launch(arrayOf("text/markdown", "text/plain", "*/*")) }) {
                    Text("IMPORT MARKDOWN")
                }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = state::search,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search notes...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { state.search("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.selectedTag == null && state.query.isBlank(),
                        onClick = { state.selectTag(null) },
                        label = { Text("ALL") },
                    )
                }
                items(listOf("LOCAL-FIRST", "RECENT")) { label ->
                    AssistChip(onClick = {}, label = { Text(label) })
                }
            }
            if (state.tags.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.tags, key = { it.first }) { (tag, count) ->
                        FilterChip(
                            selected = state.selectedTag == tag,
                            onClick = { state.selectTag(if (state.selectedTag == tag) null else tag) },
                            label = { Text("#$tag  $count") },
                        )
                    }
                }
            }
            val problem = state.vaultProblem
            val summary = when {
                problem != null -> problem
                state.query.isNotBlank() -> "${state.notes.size} results"
                state.selectedTag != null -> "${state.notes.size} notes with #${state.selectedTag}"
                else -> "${state.noteCount} notes"
            }
            Text(
                summary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NetworkGraphCard(state.graph)
            if (state.notes.isEmpty() && problem == null) {
                if (state.query.isBlank() && state.selectedTag == null) {
                    EmptyVaultState(
                        onNewNote = { state.draft = NoteDraft(null, "") },
                        onImport = { importPicker.launch(arrayOf("text/markdown", "text/plain", "*/*")) },
                    )
                } else EmptyState("Nothing matches.")
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.notes, key = { it.id }) { note -> NoteCard(note) { state.open(note.id) } }
            }
        }
        FloatingActionButton(
            onClick = { state.draft = NoteDraft(null, "") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Neon.Coral,
            contentColor = Neon.Void,
        ) { Icon(Icons.Filled.Add, contentDescription = "New note") }
    }
}

@Composable
internal fun EmptyState(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyVaultState(onNewNote: () -> Unit, onImport: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Neon.Panel),
        border = BorderStroke(1.dp, Neon.Line),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("No notes yet", style = MaterialTheme.typography.titleMedium, color = Neon.Cyan)
            Text(
                "Start with a local note or import Markdown. Nothing leaves this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onNewNote) { Text("NEW NOTE") }
                TextButton(onClick = onImport) { Text("IMPORT") }
            }
        }
    }
}

@Composable
private fun NoteCard(note: CaptureSummary, onClick: () -> Unit) {
    val title = note.title ?: note.text.lineSequence().firstOrNull().orEmpty()
    val body = note.text.lineSequence().drop(1).joinToString(" ").trim().ifEmpty { note.text }
    val priority = title.contains("PROT-01", ignoreCase = true)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, if (priority) Neon.Coral else MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (priority) Neon.Coral else Neon.Cyan,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (priority) Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = Neon.Coral)
            }
            Spacer(Modifier.height(4.dp))
            InlineMarkdown(body, maxLines = 3)
            Spacer(Modifier.height(8.dp))
            val tags = Regex("""#[\p{L}\p{N}_-]+""").findAll(note.text).map { it.value }.distinct().take(3).toList()
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = Neon.Yellow) }
                }
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "${formatDate(note.capturedAt)} · ${note.source}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

}

@Composable
fun SeBrainMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val coral = Neon.Coral
        val bone = Neon.Cyan
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.43f
        drawCircle(bone, radius = radius, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
        val brain = Path().apply {
            moveTo(center.x, center.y - radius * 0.62f)
            cubicTo(center.x - radius * 0.45f, center.y - radius * 0.82f, center.x - radius * 0.72f, center.y - radius * 0.38f, center.x - radius * 0.54f, center.y - radius * 0.05f)
            cubicTo(center.x - radius * 0.78f, center.y + radius * 0.12f, center.x - radius * 0.52f, center.y + radius * 0.58f, center.x - radius * 0.15f, center.y + radius * 0.48f)
            cubicTo(center.x - radius * 0.05f, center.y + radius * 0.75f, center.x + radius * 0.05f, center.y + radius * 0.75f, center.x + radius * 0.15f, center.y + radius * 0.48f)
            cubicTo(center.x + radius * 0.52f, center.y + radius * 0.58f, center.x + radius * 0.78f, center.y + radius * 0.12f, center.x + radius * 0.54f, center.y - radius * 0.05f)
            cubicTo(center.x + radius * 0.72f, center.y - radius * 0.38f, center.x + radius * 0.45f, center.y - radius * 0.82f, center.x, center.y - radius * 0.62f)
        }
        drawPath(brain, coral, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
        drawLine(bone, Offset(center.x, center.y - radius * 0.55f), Offset(center.x, center.y + radius * 0.52f), 2f, StrokeCap.Round)
        drawLine(bone, Offset(center.x - radius * 0.48f, center.y - radius * 0.2f), Offset(center.x - radius * 0.08f, center.y - radius * 0.04f), 2f, StrokeCap.Round)
        drawLine(bone, Offset(center.x + radius * 0.48f, center.y - radius * 0.2f), Offset(center.x + radius * 0.08f, center.y - radius * 0.04f), 2f, StrokeCap.Round)
        drawLine(bone, Offset(center.x - radius * 0.42f, center.y + radius * 0.25f), Offset(center.x - radius * 0.08f, center.y + radius * 0.18f), 2f, StrokeCap.Round)
        drawLine(bone, Offset(center.x + radius * 0.42f, center.y + radius * 0.25f), Offset(center.x + radius * 0.08f, center.y + radius * 0.18f), 2f, StrokeCap.Round)
        listOf(
            Offset(center.x - radius * 0.48f, center.y - radius * 0.2f),
            Offset(center.x + radius * 0.48f, center.y - radius * 0.2f),
            Offset(center.x - radius * 0.42f, center.y + radius * 0.25f),
            Offset(center.x + radius * 0.42f, center.y + radius * 0.25f),
        ).forEach { drawCircle(coral, 2.5f, it) }
    }
}

@Composable
private fun NetworkGraphCard(graph: NoteGraph?) {
    val nodes = graph?.nodes.orEmpty().take(9)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        colors = CardDefaults.cardColors(containerColor = Neon.Panel),
        border = BorderStroke(1.dp, Neon.Line),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NETWORK GRAPH", style = MaterialTheme.typography.labelLarge, color = Neon.Coral)
                Spacer(Modifier.weight(1f))
                Text(
                    if (graph == null) "SYNCING" else "${graph.nodes.count { it.id != null }} NODES",
                    style = MaterialTheme.typography.labelSmall,
                    color = Neon.Dim,
                )
            }
            Text("Local note topology", style = MaterialTheme.typography.titleMedium, color = Neon.Cyan)
            Spacer(Modifier.height(8.dp))
            if (nodes.isEmpty()) {
                Text(
                    if (graph == null) "Syncing local index…" else "Create or import a note to build the network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Neon.Dim,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else Box(Modifier.fillMaxWidth().height(150.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width * 0.5f, size.height * 0.5f)
                    val points = nodes.mapIndexed { index, _ ->
                        if (nodes.size == 1) {
                            center
                        } else {
                            val angle = index * 6.28318f / nodes.size
                            Offset(
                                center.x + kotlin.math.cos(angle) * size.width * 0.34f,
                                center.y + kotlin.math.sin(angle) * size.height * 0.32f,
                            )
                        }
                    }
                    graph?.edges
                        ?.filter { (a, b) -> a in points.indices && b in points.indices }
                        ?.forEach { (a, b) ->
                            drawLine(Neon.Line, points[a], points[b], 1.2f)
                        }
                    points.forEachIndexed { index, point ->
                        drawCircle(
                            if (nodes[index].id == null) Neon.Dim else Neon.Coral,
                            7f,
                            point,
                        )
                    }
                }
                Text(
                    "${graph?.edges?.size ?: 0} connections",
                    style = MaterialTheme.typography.labelSmall,
                    color = Neon.Dim,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NoteDetailScreen(state: AppState, detail: NoteDetail) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmShare by remember { mutableStateOf(false) }
    val note = detail.note
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let { state.exportMarkdown(note.id, it) }
    }
    BackHandler { state.closeDetail() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = state::closeDetail) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { state.draft = NoteDraft(note.id, note.text) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                    IconButton(onClick = { confirmShare = true }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                    TextButton(onClick = { exportPicker.launch(com.sebrain.cognition.MarkdownInterop.fileName(note.title)) }) {
                        Text("EXPORT")
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text(note.title.ifEmpty { "Untitled" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "${formatDate(note.capturedAt)} · ${note.source}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            MarkdownBody(note.text, note.title, onLinkClick = state::openByTitle)
            if (note.tags.isNotEmpty()) {
                Section("Tags")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    note.tags.forEach { tag ->
                        AssistChip(onClick = { state.closeDetail(); state.selectTag(tag) }, label = { Text("#$tag") })
                    }
                }
            }
            LinkSection("Links to", detail.linksTo, state)
            LinkSection("Linked from", detail.linkedFrom, state)
            Spacer(Modifier.height(32.dp))
        }
    }
    if (confirmDelete) {
        Confirm("Delete this note?", "It cannot be recovered.", "Delete", onDismiss = { confirmDelete = false }) { state.delete(note.id) }
    }
    if (confirmShare) {
        Confirm(
            "Share this note?",
            "Its text will leave the vault, unencrypted, to the app you choose.",
            "Share",
            onDismiss = { confirmShare = false },
        ) {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, note.title).putExtra(Intent.EXTRA_TEXT, note.text),
                    null,
                ),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkSection(title: String, notes: List<CaptureSummary>, state: AppState) {
    if (notes.isEmpty()) return
    Section(title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        notes.forEach { other -> AssistChip(onClick = { state.open(other.id) }, label = { Text(other.title.orEmpty()) }) }
    }
}

@Composable
internal fun Confirm(title: String, message: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(state: AppState, draft: NoteDraft) {
    var text by remember(draft) { mutableStateOf(draft.text) }
    var error by remember(draft) { mutableStateOf<String?>(null) }
    val close = { state.draft = null }
    BackHandler(onBack = close)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft.id == null) "New note" else "Edit note") },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = "Discard") } },
                actions = {
                    IconButton(
                        enabled = text.isNotBlank(),
                        onClick = { state.save(draft, text.trim()) { saved -> if (!saved) error = "Another note already has exactly this text" } },
                    ) { Icon(Icons.Filled.Check, contentDescription = "Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
            TextField(
                value = text,
                onValueChange = { text = it; error = null },
                modifier = Modifier.fillMaxSize(),
                placeholder = { Text("First line is the title.\n\nUse #tags and [[links to other notes]].") },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
