package com.sebrain.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.sebrain.core.vault.Brain

/**
 * The brain every screen is scoped to. UNIVERSAL sees every note; a sub-brain sees only the
 * notes of its folder, with its own graph, search and chats. A lock marks a private brain, in
 * which only the phone's model can be used.
 */
@Composable
fun BrainBar(state: AppState, modifier: Modifier = Modifier) {
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { state.loadBrains() }
    Column(modifier.fillMaxWidth()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.currentBrain == null,
                    onClick = { state.switchBrain(null) },
                    label = { Text("UNIVERSAL") },
                    enabled = !state.generating,
                )
            }
            items(state.subBrains, key = { it.id }) { brain ->
                FilterChip(
                    selected = state.currentBrain?.id == brain.id,
                    onClick = { state.switchBrain(brain) },
                    label = { Text((if (brain.private) "🔒 " else "") + brain.name + "  ${brain.notes}") },
                    enabled = !state.generating,
                )
            }
            item { AssistChip(onClick = { creating = true }, label = { Text("+ BRAIN") }) }
        }
        state.currentBrain?.let { brain ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (brain.private) "Private brain: only the phone's AI, its notes never leave" else "External AI allowed here, only with what you send",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { state.setBrainPrivate(brain, !brain.private) }) {
                    Text(if (brain.private) "ALLOW EXTERNAL AI" else "MAKE PRIVATE")
                }
            }
        }
    }
    if (creating) NewBrainDialog(onDismiss = { creating = false }) { name, private ->
        creating = false
        state.createBrain(name, private)
    }
}

@Composable
private fun NewBrainDialog(onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var private by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && name.trim().length <= 60 && name.none { it in "/\\:*?\"<>|" } && !name.trim().startsWith(".")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New brain") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A brain is a folder of notes with its own graph, search and chats. The universal brain still sees all of them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, isError = name.isNotEmpty() && !valid)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = private, onCheckedChange = { private = it })
                    Text("Private: only the phone's AI", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim(), private) }, enabled = valid) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Chooses the brain a note moves to. "No brain" takes it out of every folder, to the universal brain only. */
@Composable
fun MoveNoteDialog(brains: List<Brain>, current: String?, onDismiss: () -> Unit, onMove: (Brain?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to brain") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onMove(null) }, enabled = current != null) { Text("No brain (universal only)") }
                brains.forEach { brain ->
                    TextButton(onClick = { onMove(brain) }, enabled = !brain.name.equals(current, ignoreCase = true)) {
                        Text((if (brain.private) "🔒 " else "") + brain.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
