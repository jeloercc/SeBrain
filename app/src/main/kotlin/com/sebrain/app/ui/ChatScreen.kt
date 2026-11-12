package com.sebrain.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(state: AppState, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var chatsOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Asked once, on the first message: it lets SeBrain say when a reply finished in the background.
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var askedNotifications by remember { mutableStateOf(false) }
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
                        model != null -> "On device · ${state.modelBackend} · say “make a note about…” to save an answer"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { state.searchConversations(""); chatsOpen = true }, enabled = !state.generating) { Text("Chats") }
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
            EmptyState("Ask about your notes: SeBrain answers from them and from how they are linked.\nSay “make a note about…” to save an answer, linked to related notes.\n\nChats are saved, encrypted, under Chats. Everything runs on this phone.")
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(state.messages) { i, message ->
                Bubble(message)
                if (!message.fromUser && !message.isAction && message.text.isNotBlank()) {
                    if (message.saved) {
                        Text("✓ Saved as a note", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    } else if (!state.generating) {
                        Row {
                            TextButton(onClick = { state.saveAsNote(i) }) { Text("Save as note") }
                            // Only under the last reply: it looks at the whole conversation.
                            if (i == state.messages.lastIndex) {
                                TextButton(onClick = state::suggestMemories) { Text("Suggest memories") }
                            }
                        }
                    }
                }
            }
        }
        if (state.memorySuggestions.isNotEmpty() || state.memoryNotice != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    state.memoryNotice ?: "Remember this? Saved only if you accept.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.memorySuggestions.forEach { fact ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fact, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { state.acceptSuggestion(fact) }) { Text("Save") }
                        IconButton(onClick = { state.dismissSuggestion(fact) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
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
                        if (!askedNotifications &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            askedNotifications = true
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
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
    if (chatsOpen) {
        ChatsSheet(state) { chatsOpen = false }
    }
}

/** Saved conversations: search them, open one to continue it, start a new one, or delete one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsSheet(state: AppState, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { state.newConversation(); onClose() }) { Text("+ New chat") }
            }
            OutlinedTextField(
                value = state.conversationQuery,
                onValueChange = state::searchConversations,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search in past chats") },
                singleLine = true,
            )
            if (state.conversations.isEmpty()) {
                Text(
                    if (state.conversationQuery.isBlank()) "No saved chats yet" else "No chat matches",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(state.conversations, key = { it.id }) { chat ->
                    Row(
                        Modifier.fillMaxWidth().clickable { state.openConversation(chat.id); onClose() }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                chat.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (chat.id == state.conversationId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            chat.snippet?.let {
                                Text(
                                    it.replace('\n', ' '),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "${chat.messages} messages · ${chat.updatedAt.take(10)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { state.deleteConversation(chat.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete chat")
                        }
                    }
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
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .background(if (message.fromUser) colors.primaryContainer else colors.surfaceContainerHigh, MaterialTheme.shapes.medium)
                .border(1.dp, if (message.fromUser) colors.primary.copy(alpha = 0.5f) else colors.outline, MaterialTheme.shapes.medium)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            MarkdownBody(message.text.ifEmpty { "…" }, "script", if (message.fromUser) colors.onPrimaryContainer else colors.onSurface)
        }
    }
}
