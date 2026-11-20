package com.sebrain.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The review of a note written in the chat: what was repaired, what needs attention, and the
 * note exactly as it will be stored. Nothing is written until SAVE.
 */
@Composable
fun NewNoteScreen(state: AppState, note: AppState.NewNote) {
    Dialog(onDismissRequest = state::discardNewNote, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Palette.Ink, contentColor = Palette.Bone) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("New note" + if (state.newNotesLeft > 0) " · ${state.newNotesLeft} more after this" else "")
                Text(note.title.ifEmpty { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                Text("Not saved until you tap SAVE.", style = MaterialTheme.typography.labelMedium, color = Palette.Dim)
                note.duplicate?.let { (_, title) ->
                    Text(
                        "! \"$title\" already answers to this name. Add this text to it instead of making a second note.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Coral,
                    )
                }
                note.warnings.forEach { Text("! $it", style = MaterialTheme.typography.bodySmall, color = Palette.Coral) }
                note.fixes.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall, color = Palette.Olive) }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    MarkdownBody(note.preview, note.title)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = state::discardNewNote, modifier = Modifier.weight(1f)) { Text("DISCARD") }
                    if (note.duplicate != null) {
                        Button(onClick = state::addNewNoteToExisting, modifier = Modifier.weight(1f)) { Text("ADD TO IT") }
                    } else {
                        Button(onClick = state::saveNewNote, modifier = Modifier.weight(1f)) { Text("SAVE") }
                    }
                }
            }
        }
    }
}
