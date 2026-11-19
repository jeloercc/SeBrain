package com.sebrain.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sebrain.app.profile.DeviceSpec
import com.sebrain.app.profile.Hardware
import com.sebrain.app.profile.ModelAdvice
import com.sebrain.app.profile.ModelAdvisor
import com.sebrain.app.profile.Role

/** Profile › Device & models: what this phone has and which models suit it. */
@Composable
fun DeviceScreen(state: AppState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader("Profile › Device & models", onBack)
        ModelAdviceList(state)
    }
}

/** The detected phone, the advice for it, and the download or import of each model. */
@Composable
fun ModelAdviceList(state: AppState) {
    val context = LocalContext.current
    val spec = remember { DeviceSpec.current(context) }
    val hub = state.modelHub
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(hub::importFile) }
    LaunchedEffect(Unit) { hub.refreshInstalled() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().clip(CardCut).background(Neon.Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Detected")
            Text(
                "${spec.model} · ${spec.soc} · ${formatBytes(spec.totalRamBytes)} memory · ${formatBytes(spec.freeBytes)} free",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (ModelAdvisor.hasTensorNpu(spec)) {
                    "The NPU runs search. Chat runs on the GPU: these chat models are not compiled for the Tensor NPU."
                } else {
                    "No Tensor NPU: search runs on the CPU and chat on the GPU."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Neon.Dim,
            )
        }
        val busy = hub.hubBusy || hub.downloadProgress != null
        ModelAdvisor.advise(spec).forEach { advice ->
            AdviceCard(
                advice = advice,
                installed = hub.installed.any { it.fileName == advice.model.fileName },
                busy = busy,
                onDownload = { hub.prepareDownload(advice.model) },
                onImport = { importPicker.launch(arrayOf("*/*")) },
            )
        }
        hub.downloadProgress?.let { progress ->
            LinearProgressIndicator(progress = { progress }, color = Neon.Coral, modifier = Modifier.fillMaxWidth())
            Text("Downloading… ${(progress * PERCENT).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Neon.Dim)
        }
        hub.hubMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Neon.Dim) }
    }

    hub.pendingDownload?.let { file ->
        AlertDialog(
            onDismissRequest = hub::cancelDownload,
            title = { Text("Download ${file.fileName}?") },
            text = {
                Text(
                    "${formatBytes(file.sizeBytes)} from huggingface.co/${file.repo}. Wi-Fi is recommended. " +
                        "The file is checked against the SHA-256 pinned in SeBrain before it is used.",
                )
            },
            confirmButton = { TextButton(onClick = hub::confirmDownload) { Text("DOWNLOAD") } },
            dismissButton = { TextButton(onClick = hub::cancelDownload) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun AdviceCard(advice: ModelAdvice, installed: Boolean, busy: Boolean, onDownload: () -> Unit, onImport: () -> Unit) {
    val emphasized = advice.recommended
    val frame = if (emphasized) Modifier.clip(CardCut).background(Neon.PanelHigh) else Modifier.border(1.dp, Neon.Line)
    Column(Modifier.fillMaxWidth().then(frame).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (advice.role == Role.SEARCH) "Search by meaning" else "Chat · ${advice.model.description}",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (emphasized) Neon.Cyan else Neon.Dim,
                modifier = Modifier.weight(1f),
            )
            Tag(advice.hardware.name, filled = if (emphasized) hardwareColor(advice.hardware) else null)
        }
        Text(advice.reason, style = MaterialTheme.typography.bodySmall, color = if (emphasized) Neon.Cyan else Neon.Dim)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            when {
                installed -> SectionLabel("Installed")
                else -> {
                    if (emphasized) SectionLabel("Recommended")
                    if (!busy && advice.model.repo != null) TextAction("Download", onDownload)
                    if (!busy) TextAction("Import from file", onImport, color = Neon.Dim)
                }
            }
        }
    }
}

private fun hardwareColor(hardware: Hardware) = when (hardware) {
    Hardware.NPU -> Neon.Yellow
    Hardware.GPU -> Neon.Cyan
    Hardware.CPU -> Neon.Dim
}

private const val PERCENT = 100
