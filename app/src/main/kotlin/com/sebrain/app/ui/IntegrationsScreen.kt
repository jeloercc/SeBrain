package com.sebrain.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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

@Composable
fun IntegrationsScreen(state: AppState, modifier: Modifier = Modifier) {
    var filter by remember { mutableStateOf("") }
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
    val visibleApps = state.integrationApps.filter {
        filter.isBlank() ||
            it.label.contains(filter, ignoreCase = true) ||
            it.packageName.contains(filter, ignoreCase = true)
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("BACKUP", color = Neon.Coral, style = MaterialTheme.typography.labelLarge)
            Text(
                "Exports an encrypted logical snapshot. The destination is chosen through Android SAF.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(onClick = { showBackupDialog = true }) { Text("EXPORT ENCRYPTED BACKUP") }
            Button(onClick = { showRestoreDialog = true }) { Text("VALIDATE BACKUP") }
            state.backupStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Neon.Coral)
            }
        }
        item {
            Text("INTEGRATIONS", color = Neon.Coral, style = MaterialTheme.typography.labelLarge)
            Text(
                "Only selected notification packages can enter the encrypted SeBrain inbox. " +
                    "Access is disabled by default.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (state.notificationAccessEnabled) "Android access: enabled" else "Android access: not enabled",
                        color = if (state.notificationAccessEnabled) Neon.Coral else Neon.Dim,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "This permission lets SeBrain read notifications from the packages you select below.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = state::openNotificationSettings) {
                        Text("OPEN ANDROID SETTINGS")
                    }
                }
                if (showBackupDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            passphrase = ""
                            showBackupDialog = false
                        },
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
                                    pendingPassphrase = passphrase
                                    passphrase = ""
                                    showBackupDialog = false
                                    backupPicker.launch("sebrain-backup.sbbk")
                                },
                                enabled = passphrase.isNotEmpty(),
                            ) { Text("CHOOSE DESTINATION") }
                        },
                        dismissButton = {
                            Button(onClick = {
                                passphrase = ""
                                showBackupDialog = false
                            }) { Text("CANCEL") }
                        },
                    )
                }
                if (showRestoreDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            passphrase = ""
                            showRestoreDialog = false
                        },
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
                                    pendingRestorePassphrase = passphrase
                                    passphrase = ""
                                    showRestoreDialog = false
                                    restorePicker.launch(arrayOf("application/octet-stream", "*/*"))
                                },
                                enabled = passphrase.isNotEmpty(),
                            ) { Text("CHOOSE BACKUP") }
                        },
                        dismissButton = {
                            Button(onClick = {
                                passphrase = ""
                                showRestoreDialog = false
                            }) { Text("CANCEL") }
                        },
                    )
                }
            }
        }
        item {
            Text(
                "${state.notificationPackages.size} package(s) selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.graphSources.isNotEmpty()) {
            item {
                Text("MINI-BRAINS", color = Neon.Coral, style = MaterialTheme.typography.labelLarge)
                Text(
                    "Save an encrypted view over one or more capture sources.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                androidx.compose.material3.OutlinedTextField(
                    value = state.brainName,
                    onValueChange = { state.brainName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Brain name") },
                    singleLine = true,
                )
            }
            items(state.graphSources, key = { "brain-$it" }) { source ->
                val checked = source in state.brainSources
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(source, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Checkbox(checked = checked, onCheckedChange = { state.toggleBrainSource(source, it) })
                    }
                }
            }
            item {
                Button(
                    onClick = state::saveBrain,
                    enabled = state.brainName.trim().isNotEmpty() && state.brainSources.isNotEmpty(),
                ) {
                    Text("SAVE MINI-BRAIN")
                }
            }
            items(state.brains, key = { "saved-${it.name}" }) { brain ->
                Text(
                    "Saved: ${brain.name} · ${brain.sources.size} source(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Filter installed apps") },
                singleLine = true,
            )
        }
        if (visibleApps.isEmpty()) {
            item { Text("No installed applications match.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(visibleApps, key = { it.packageName }) { app ->
                val checked = app.packageName in state.notificationPackages
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { state.setNotificationPackageEnabled(app.packageName, it) },
                        )
                    }
                }
            }
        }
    }
}
