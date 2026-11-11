package com.sebrain.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebrain.core.vault.CaptureSummary
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
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = state::search,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search your notes") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { state.search("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
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
            if (state.notes.isEmpty() && problem == null) {
                EmptyState(
                    if (state.query.isBlank() && state.selectedTag == null) {
                        "No notes yet.\nTap + to write one, or share text from any app to SeBrain."
                    } else {
                        "Nothing matches."
                    },
                )
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
private fun NoteCard(note: CaptureSummary, onClick: () -> Unit) {
    val title = note.title ?: note.text.lineSequence().firstOrNull().orEmpty()
    val body = note.text.lineSequence().drop(1).joinToString(" ").trim().ifEmpty { note.text }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                body.replace('\n', ' '),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatDate(note.capturedAt)} · ${note.source}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            SelectionContainer { Text(note.text, style = MaterialTheme.typography.bodyLarge) }
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
