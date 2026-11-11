package com.sebrain.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(state: AppState, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { state.ensureModel() }
    val lastLength = state.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.messages.size, lastLength) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val model = state.currentModel
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        state.loadingModel -> "Loading the model…"
                        model != null -> model.description
                        else -> "No model loaded"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        state.loadingModel -> "The first load of a model can take a minute"
                        model != null -> "On device · ${state.modelBackend} · ${if (state.canAct) "can act on notes" else "answers only"}"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { menuOpen = true }, enabled = !state.loadingModel && !state.generating) { Text("Model") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (state.languageModels.isEmpty()) {
                        DropdownMenuItem(text = { Text("None installed") }, onClick = { menuOpen = false })
                    }
                    state.languageModels.forEach { option ->
                        DropdownMenuItem(text = { Text(option.description) }, onClick = { menuOpen = false; state.ensureModel(option) })
                    }
                }
            }
        }
        if (state.loadingModel || state.generating) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        state.chatProblem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.messages.isEmpty() && state.chatProblem == null) {
            EmptyState("Ask about your notes, or ask SeBrain to create, tag or link one.\n\nEverything runs on this phone.")
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(state.messages) { _, message -> Bubble(message) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 5,
                shape = MaterialTheme.shapes.extraLarge,
            )
            FilledIconButton(
                onClick = {
                    if (state.generating) {
                        state.stopGenerating()
                    } else {
                        state.ask(input)
                        input = ""
                    }
                },
                enabled = state.generating || input.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            ) {
                if (state.generating) {
                    Icon(Icons.Filled.Close, contentDescription = "Stop")
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    val colors = MaterialTheme.colorScheme
    if (message.isAction) {
        Text("✓ ${message.text}", style = MaterialTheme.typography.labelLarge, color = colors.secondary, modifier = Modifier.padding(horizontal = 4.dp))
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
        SelectionContainer {
            Text(
                message.text.ifEmpty { "…" },
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(if (message.fromUser) colors.primaryContainer else colors.surfaceContainerHigh, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (message.fromUser) colors.onPrimaryContainer else colors.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
