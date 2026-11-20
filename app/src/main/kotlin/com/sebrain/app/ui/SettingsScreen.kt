package com.sebrain.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sebrain.core.crypto.InboxMode

/** Profile › Settings: only options the app really has. */
@Composable
fun SettingsScreen(state: AppState, onBack: () -> Unit, onOpenProviders: () -> Unit, modifier: Modifier = Modifier) {
    val settings = state.settings
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageHeader("Profile › Settings", onBack)

        SectionLabel("Network", Modifier.padding(top = 8.dp))
        SwitchRow(
            title = "Require Tailscale",
            subtitle = "Downloads and AI providers connect only through your tailnet. Nothing connects while Tailscale is off.",
            checked = settings.requireTailscale,
            onChange = settings::updateRequireTailscale,
        )
        NavRow("External AI providers", "Off unless you set one up. Keys stay encrypted.", onOpenProviders)

        SectionLabel("Lock & inbox", Modifier.padding(top = 12.dp))
        InboxModeChoice(settings.inboxMode, settings::updateInboxMode)
        Text("Close notes after", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
        Text(
            "Your notes also close whenever the phone locks.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Dim,
        )
        SettingsState.IDLE_CHOICES.forEach { minutes ->
            ChoiceRow(
                title = if (minutes == 1) "1 minute unused" else "$minutes minutes unused",
                subtitle = null,
                selected = settings.idleMinutes == minutes,
                onSelect = { settings.updateIdleMinutes(minutes) },
            )
        }

        SectionLabel("Screen", Modifier.padding(top = 12.dp))
        SwitchRow(
            title = "Allow screenshots for 1 hour",
            subtitle = "SeBrain blocks screenshots and screen recording to protect your notes. This lifts the block for one hour; it comes back on by itself.",
            checked = settings.screenshotsAllowed(),
            onChange = settings::allowScreenshots,
        )

        SectionLabel("AI", Modifier.padding(top = 12.dp))
        Row(
            Modifier.fillMaxWidth().border(1.dp, Palette.Line).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Gemini Nano", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = Palette.Dim)
                Text("Google's on-device model. Not available yet.", style = MaterialTheme.typography.bodySmall, color = Palette.Dim)
            }
            Tag("Pending decision")
        }
    }
}

/** Balanced or Strict: which inbox key new shares are encrypted to. */
@Composable
fun InboxModeChoice(selected: InboxMode, onSelect: (InboxMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceRow(
            title = "Balanced (recommended)",
            subtitle = "Shares are saved encrypted, even when the phone is locked. SeBrain may prepare them before you unlock.",
            selected = selected == InboxMode.BALANCED,
            onSelect = { onSelect(InboxMode.BALANCED) },
        )
        ChoiceRow(
            title = "Strict",
            subtitle = "Shares are saved encrypted, and can only be opened after you unlock the phone.",
            selected = selected == InboxMode.STRICT,
            onSelect = { onSelect(InboxMode.STRICT) },
        )
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(if (selected) Palette.PanelHigh else Palette.Panel)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Palette.Coral))
        Column(Modifier.padding(start = 8.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.Dim)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(Palette.Panel)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.Dim)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Coral, checkedThumbColor = Palette.Ink),
        )
    }
}
