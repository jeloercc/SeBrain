package com.sebrain.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebrain.app.ProviderStore
import com.sebrain.core.net.RemoteProvider

/** The external AI providers, in the Models tab: add one with its key, choose it, or remove it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProvidersSection(state: AppState) {
    var editing by remember { mutableStateOf<RemoteProvider?>(null) }
    LaunchedEffect(Unit) { state.loadProviders() }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("EXTERNAL AI (API)", color = Neon.Coral, style = MaterialTheme.typography.labelLarge)
        Text(
            "Any provider with an OpenAI-style API: OpenRouter, OpenAI, Anthropic, Gemini, Groq or your own server. " +
                "Only what you write, and notes you attach, leave the phone; you see it before it is sent. " +
                "Keys are stored encrypted. Use the phone's model for anything private.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.providers.forEach { provider ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${provider.model} · ${provider.baseUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { editing = provider }) { Text("EDIT") }
                    TextButton(onClick = { state.removeProvider(provider.id) }) { Text("REMOVE") }
                }
            }
        }
        Text("Add a provider", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProviderStore.PRESETS.forEach { preset -> AssistChip(onClick = { editing = preset }, label = { Text(preset.name) }) }
        }
        state.providerProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    editing?.let { start -> ProviderDialog(start, onDismiss = { editing = null }) { provider, key -> state.saveProvider(provider, key); editing = null } }
}

@Composable
private fun ProviderDialog(start: RemoteProvider, onDismiss: () -> Unit, onSave: (RemoteProvider, String) -> Unit) {
    var name by remember { mutableStateOf(start.name) }
    var baseUrl by remember { mutableStateOf(start.baseUrl) }
    var model by remember { mutableStateOf(start.model) }
    var key by remember { mutableStateOf("") }
    val editingExisting = start.id.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingExisting) "Edit ${start.name}" else "Add ${start.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Address (https://…)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(model, { model = it }, label = { Text("Model, e.g. openai/gpt-5.2") }, singleLine = true)
                OutlinedTextField(
                    key, { key = it },
                    label = { Text(if (editingExisting) "API key (blank keeps the stored one)" else "API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(start.copy(name = name, baseUrl = baseUrl, model = model), key) },
                enabled = name.isNotBlank() && baseUrl.startsWith("https://") && model.isNotBlank() && (editingExisting || key.isNotBlank()),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Choosing notes to send with the next message to the external model. */
@Composable
fun AttachNotesDialog(state: AppState, onDismiss: () -> Unit) {
    var words by remember { mutableStateOf("") }
    LaunchedEffect(words) { state.searchAttachable(words) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach notes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Attached notes are sent to ${state.remoteProvider?.name ?: "the external model"} with your next message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(words, { words = it }, placeholder = { Text("Search notes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(state.attachCandidates, key = { it.first }) { (id, title) ->
                        val attached = state.attachedNotes.any { it.first == id }
                        FilterChip(
                            selected = attached,
                            onClick = { if (attached) state.detachNote(id) else state.attachNote(id, title) },
                            label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Exactly what is about to leave the phone, and to whom. Nothing is sent without SEND. */
@Composable
fun OutgoingDialog(state: AppState, request: AppState.OutgoingRequest) {
    var showText by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = state::cancelOutgoing,
        title = { Text("Send to ${request.provider.name}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model: ${request.provider.model}", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (request.notes.isEmpty()) {
                        "Only your message leaves the phone. No note is sent."
                    } else {
                        "Your message and ${request.notes.size} note(s) leave the phone: " + request.notes.joinToString(", ") { it.title }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${request.characters} characters in total. ${request.provider.name} and the company behind the model can read them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showText = !showText }) { Text(if (showText) "HIDE WHAT IS SENT" else "SHOW WHAT IS SENT") }
                if (showText) Text(request.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { showText = false })
            }
        },
        confirmButton = { Button(onClick = state::confirmOutgoing) { Text("SEND") } },
        dismissButton = { TextButton(onClick = state::cancelOutgoing) { Text("Cancel") } },
    )
}
