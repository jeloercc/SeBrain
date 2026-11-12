package com.sebrain.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sebrain.app.privacy.AUDIT_DAYS
import com.sebrain.app.privacy.AppPrivacy

@Composable
fun PrivacyScreen(state: AppState, modifier: Modifier = Modifier) {
    var filter by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var showSystem by remember { mutableStateOf(false) }
    val visibleApps = state.privacyApps.filter {
        (showSystem || !it.system) &&
            (filter.isBlank() || it.label.contains(filter, ignoreCase = true) || it.packageName.contains(filter, ignoreCase = true))
    }
    val scanned = state.privacyApps.count { it.trackers != null }
    val withTrackers = state.privacyApps.count { !it.trackers.isNullOrEmpty() }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("PRIVACY", color = Neon.Coral, style = MaterialTheme.typography.labelLarge)
            Text(
                "What your apps can reach: trackers inside them, sensitive permissions you granted, " +
                    "and how much data they used. Everything is checked on the phone; nothing is sent or stored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val progress = state.scanProgress
                    Text(
                        when {
                            progress != null -> "Scanning apps for trackers… $progress"
                            scanned == 0 -> "Trackers: not scanned yet"
                            else -> "Trackers found in $withTrackers of $scanned apps"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Button(onClick = state::scanTrackers, enabled = progress == null && state.privacyApps.isNotEmpty()) {
                        Text(if (scanned == 0) "SCAN FOR TRACKERS" else "SCAN AGAIN")
                    }
                    if (!state.usageAccess) {
                        Text(
                            "Data use per app needs usage access. Android asks you to turn it on for SeBrain.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = state::openUsageAccessSettings) { Text("ALLOW USAGE ACCESS") }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Filter apps") },
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
                Text("Show system apps", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(visibleApps, key = { it.packageName }) { app ->
            AppPrivacyCard(
                app = app,
                expanded = expanded == app.packageName,
                onToggle = { expanded = if (expanded == app.packageName) null else app.packageName },
                onOpenSettings = { state.openAppSettings(app.packageName) },
            )
        }
        item { BackupSection(state) }
        if (state.graphSources.isNotEmpty()) item { MiniBrainSection(state) }
    }
}

@Composable
private fun AppPrivacyCard(app: AppPrivacy, expanded: Boolean, onToggle: () -> Unit, onOpenSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            val trackers = app.trackers
            val summary = listOfNotNull(
                when {
                    trackers == null -> null
                    trackers.isEmpty() -> "no known trackers"
                    else -> "${trackers.size} tracker(s)"
                },
                if (app.sensitive.isNotEmpty()) "${app.sensitive.size} sensitive permission(s)" else null,
                app.bytes?.let { "${formatBytes(it)} in $AUDIT_DAYS days" },
                if (!app.hasInternet) "no internet" else null,
            ).joinToString(" · ")
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!trackers.isNullOrEmpty()) Neon.Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Neon.Dim)
                if (!trackers.isNullOrEmpty()) {
                    Text("Trackers", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    trackers.forEach {
                        Text(
                            "• ${it.name} (${it.category.name.lowercase().replace('_', ' ')})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (app.sensitive.isNotEmpty()) {
                    Text("Granted", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    Text(app.sensitive.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 6.dp)) {
                    Text("MANAGE PERMISSIONS")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

@Composable
private fun BackupSection(state: AppState) {
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var pendingPassphrase by remember { mutableStateOf("") }
    var pendingRestorePassphrase by remember { mutableStateOf("") }
    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null && pendingPassphrase.isNotEmpty()) {
            state.exportBackup(uri, pendingPassphrase.toCharArray())
        }
        pendingPassphrase = ""
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pendingRestorePassphrase.isNotEmpty()) {
            state.restoreBackup(uri, pendingRestorePassphrase.toCharArray())
        }
        pendingRestorePassphrase = ""
    }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("BACKUP", color = Neon.Coral, style = MaterialTheme.typography.labelLarge)
        Text(
            "Exports an encrypted logical snapshot. The destination is chosen through Android SAF.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { showBackupDialog = true }) { Text("EXPORT ENCRYPTED BACKUP") }
        Button(onClick = { showRestoreDialog = true }) { Text("VALIDATE BACKUP") }
        state.backupStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Neon.Coral)
        }
    }
    if (showBackupDialog || showRestoreDialog) {
        val restoring = showRestoreDialog
        val close = {
            passphrase = ""
            showBackupDialog = false
            showRestoreDialog = false
        }
        AlertDialog(
            onDismissRequest = close,
            title = { Text("Backup passphrase") },
            text = {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (restoring) {
                            pendingRestorePassphrase = passphrase
                            close()
                            restorePicker.launch(arrayOf("application/octet-stream", "*/*"))
                        } else {
                            pendingPassphrase = passphrase
                            close()
                            backupPicker.launch("sebrain-backup.sbbk")
                        }
                    },
                    enabled = passphrase.isNotEmpty(),
                ) { Text(if (restoring) "CHOOSE BACKUP" else "CHOOSE DESTINATION") }
            },
            dismissButton = { Button(onClick = close) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun MiniBrainSection(state: AppState) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("MINI-BRAINS", color = Neon.Coral, style = MaterialTheme.typography.labelLarge)
        Text(
            "Save an encrypted view over one or more capture sources.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.brainName,
            onValueChange = { state.brainName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Brain name") },
            singleLine = true,
        )
        state.graphSources.forEach { source ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(source, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Checkbox(
                    checked = source in state.brainSources,
                    onCheckedChange = { state.toggleBrainSource(source, it) },
                )
            }
        }
        Button(
            onClick = state::saveBrain,
            enabled = state.brainName.trim().isNotEmpty() && state.brainSources.isNotEmpty(),
        ) { Text("SAVE MINI-BRAIN") }
        state.brains.forEach { brain ->
            Text(
                "Saved: ${brain.name} · ${brain.sources.size} source(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
