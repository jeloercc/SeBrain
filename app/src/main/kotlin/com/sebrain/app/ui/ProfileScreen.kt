package com.sebrain.app.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sebrain.app.BuildConfig
import java.security.MessageDigest
import java.time.ZoneId

/** The pages under the PROFILE tab. */
enum class ProfilePage { SETTINGS, PRIVACY, BACKUP, DEVICE, PROVIDERS, GUIDES, ABOUT }

/** The PROFILE tab: a list of pages, each opened in place with BACK to return. */
@Composable
fun ProfileScreen(state: AppState, page: ProfilePage?, onPage: (ProfilePage?) -> Unit, modifier: Modifier = Modifier) {
    BackHandler(enabled = page != null) { onPage(if (page == ProfilePage.PROVIDERS) ProfilePage.SETTINGS else null) }
    val back = { onPage(null) }
    when (page) {
        null -> ProfileHome(onPage, modifier)
        ProfilePage.SETTINGS -> SettingsScreen(state, back, { onPage(ProfilePage.PROVIDERS) }, modifier)
        ProfilePage.PRIVACY -> Column(modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) { PageHeader("Profile › Privacy", back) }
            PrivacyScreen(state, Modifier.weight(1f))
        }
        ProfilePage.BACKUP -> Page("Profile › Backup & restore", back, modifier) { BackupSection(state) }
        ProfilePage.DEVICE -> DeviceScreen(state, back, modifier)
        ProfilePage.PROVIDERS -> Page("Settings › AI providers", { onPage(ProfilePage.SETTINGS) }, modifier) { ProvidersSection(state) }
        ProfilePage.GUIDES -> GuidesPage(back, modifier)
        ProfilePage.ABOUT -> Page("Profile › About", back, modifier) {
            About()
            TextAction("Show the welcome again", {
                onPage(null)
                state.settings.replayWelcome()
            })
        }
    }
}

@Composable
private fun ProfileHome(onPage: (ProfilePage) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val npu = remember { npuLine(context) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SectionLabel("Profile")
        Text(
            "${android.os.Build.MODEL} · ${ZoneId.systemDefault().id} · everything stays on this phone",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Dim,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        NavRow("Settings", "Network, lock and inbox, AI providers") { onPage(ProfilePage.SETTINGS) }
        NavRow("Privacy & security", "What your apps can reach, private brains") { onPage(ProfilePage.PRIVACY) }
        NavRow("Backup & restore", "Encrypted export, restore with undo") { onPage(ProfilePage.BACKUP) }
        NavRow("Device & models", "Suggested for this phone · $npu") { onPage(ProfilePage.DEVICE) }
        NavRow("Guides", "Memory, sharing, brains, privacy") { onPage(ProfilePage.GUIDES) }
        NavRow("About", "Version ${BuildConfig.VERSION_NAME}") { onPage(ProfilePage.ABOUT) }
    }
}

private fun npuLine(context: Context): String =
    if (com.sebrain.app.profile.ModelAdvisor.hasTensorNpu(com.sebrain.app.profile.DeviceSpec.current(context))) {
        "NPU in use: search only"
    } else {
        "no Tensor NPU"
    }

@Composable
private fun Page(caption: String, onBack: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(caption, onBack)
        content()
    }
}

@Composable
private fun GuidesPage(onBack: () -> Unit, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    BackHandler(enabled = open != null) { open = null }
    val guide = open?.let(GUIDES::getOrNull)
    if (guide != null) {
        Page("Guides › ${guide.title}", { open = null }, modifier) {
            Text(guide.title, style = MaterialTheme.typography.titleLarge)
            MarkdownBody(guide.body, guide.title)
        }
        return
    }
    Page("Profile › Guides", onBack, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            GUIDES.forEachIndexed { index, item -> NavRow(item.title, item.summary) { open = index } }
        }
    }
}

@Composable
private fun About() {
    val context = LocalContext.current
    val fingerprint = remember { signingFingerprint(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SeBrain ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Text("A private memory with an AI that runs on this phone.", style = MaterialTheme.typography.bodyMedium)
        Text("No analytics, no tracking, no crash reporting.", style = MaterialTheme.typography.bodySmall, color = Palette.Dim)
        SectionLabel("Signing certificate (SHA-256)", Modifier.padding(top = 12.dp))
        Text(fingerprint ?: "Unavailable", style = MaterialTheme.typography.labelSmall, color = Palette.Dim)
    }
}

/** The SHA-256 of the certificate this install is signed with, to compare against a known release. */
private fun signingFingerprint(context: Context): String? = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val certificate = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
    MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).joinToString(":") { "%02X".format(it) }
}.getOrNull()
