package com.sebrain.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sebrain.app.chat.NoteEdits.Change

/**
 * The review of a change the assistant proposes to a note: what the user should know, then the
 * note line by line with what is removed and what is added. Nothing is written until APPLY.
 */
@Composable
fun ProposalScreen(state: AppState, proposal: AppState.NoteProposal) {
    Dialog(onDismissRequest = state::closeReview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        proposal.label.uppercase() + if (state.proposalsLeft > 0) " · ${state.proposalsLeft} MORE AFTER THIS" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = Neon.Coral,
                        modifier = Modifier.weight(1f),
                    )
                    // Ends a review of several notes; the ones not yet seen stay as they are.
                    if (state.proposalsLeft > 0) TextButton(onClick = state::closeReview) { Text("STOP REVIEW") }
                }
                Text(proposal.title.ifEmpty { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                val added = proposal.changes.count { it.first == Change.ADDED }
                val removed = proposal.changes.count { it.first == Change.REMOVED }
                Text(
                    "$added line(s) added, $removed removed. The note is not changed until you apply.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                proposal.warnings.forEach {
                    Text("! $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(proposal.changes) { _, (change, line) ->
                        val (mark, tint, back) = when (change) {
                            Change.ADDED -> Triple("+ ", ADDED, ADDED.copy(alpha = 0.12f))
                            Change.REMOVED -> Triple("− ", REMOVED, REMOVED.copy(alpha = 0.12f))
                            Change.SAME -> Triple("  ", MaterialTheme.colorScheme.onSurfaceVariant, Color.Transparent)
                        }
                        Text(
                            mark + line,
                            modifier = Modifier.fillMaxWidth().background(back).padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = tint,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = state::discardProposal, modifier = Modifier.weight(1f)) {
                        Text(if (state.proposalsLeft > 0) "SKIP" else "DISCARD")
                    }
                    Button(onClick = state::applyProposal, modifier = Modifier.weight(1f)) { Text("APPLY") }
                }
            }
        }
    }
}

private val ADDED = Color(0xFF7CD992)
private val REMOVED = Color(0xFFFF7A6B)
