package com.sebrain.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sebrain.core.ml.KnownModel
import com.sebrain.core.net.HubFile

@Composable
fun ModelsScreen(state: AppState, modifier: Modifier = Modifier) {
    var words by remember { mutableStateOf("") }
    var toDownload by remember { mutableStateOf<HubFile?>(null) }
    var toRemove by remember { mutableStateOf<KnownModel?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(state::importFile) }
    val repo = state.hubRepo
    val results = state.hubModels
    BackHandler(enabled = repo != null) { state.closeRepo() }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                when {
                    !state.online -> "No internet connection"
                    state.tailscale -> "Online · Tailscale active"
                    else -> "Online"
                } + " · Semantic search ${if (state.semanticSearch) "on" else "off"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = words,
                onValueChange = { words = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search Hugging Face, e.g. gemma uncensored") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { state.searchHub(words) }),
            )
        }
        if (state.hubBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.downloadProgress?.let { progress ->
            item {
                Column {
                    Text("Downloading ${(progress * 100).toInt()} %", style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
        state.hubMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }

        when {
            repo != null -> {
                item { Header(repo) { TextButton(onClick = state::closeRepo) { Text("Back") } } }
                items(state.hubFiles, key = { it.path }) { file ->
                    Entry(file.fileName, "%.2f GB".format(file.sizeBytes / 1e9)) { toDownload = file }
                }
            }
            results != null -> {
                item { Header("Results") {} }
                items(results, key = { it.repo }) { model ->
                    Entry(
                        model.repo,
                        "${model.downloads} downloads · ${model.likes} likes" + if (model.gated) " · needs an account" else "",
                    ) { state.openRepo(model.repo) }
                }
            }
        }

        item {
            Header("Installed") { OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import file") } }
        }
        if (state.installed.isEmpty()) item { Text("No model installed", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.installed, key = { it.fileName }) { model ->
            Entry(model.description, model.repo ?: model.fileName) { toRemove = model }
        }
    }

    toDownload?.let { file ->
        Confirm(
            "Download and trust this model?",
            "${file.repo}\n${file.fileName}\n${"%.2f".format(file.sizeBytes / 1e9)} GB\n\nSHA-256 ${file.sha256}\n\n" +
                "It is a file from a third party. SeBrain checks the download against this hash.",
            "Download",
            onDismiss = { toDownload = null },
        ) { state.download(file) }
    }
    toRemove?.let { model ->
        Confirm("Remove this model?", "${model.description}\n${model.fileName}", "Remove", onDismiss = { toRemove = null }) { state.remove(model) }
    }
}

@Composable
private fun Header(title: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        action()
    }
}

@Composable
private fun Entry(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
