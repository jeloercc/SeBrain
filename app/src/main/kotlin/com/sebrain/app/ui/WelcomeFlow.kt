package com.sebrain.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebrain.app.R
import kotlinx.coroutines.delay

/** Where the app opens when the welcome flow ends. */
enum class WelcomeExit { NOTES, RESTORE }

private const val STEPS = 5
private const val SPLASH_MS = 1200L

/** First run only: the logo, then five steps. Every choice can be changed later in Profile. */
@Composable
fun WelcomeFlow(state: AppState, onDone: (WelcomeExit) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var exit by rememberSaveable { mutableStateOf(WelcomeExit.NOTES) }
    val finish = { target: WelcomeExit ->
        state.settings.finishWelcome()
        onDone(target)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(state::importVault)
        if (uri != null) step = STEPS
    }
    BackHandler(enabled = step > 1) { step-- }
    val back: () -> Unit = { step -= 1 }

    Box(Modifier.fillMaxSize().background(Neon.Void).systemBarsPadding()) {
        when (step) {
            0 -> Splash { step = 1 }
            1 -> Step(1, null, { TextAction("Skip", { finish(WelcomeExit.NOTES) }) }, "Get started", { step = 2 }) { Intro() }
            2 -> Step(2, back, null, "Continue", { step = 3 }) {
                Title("What stays on this phone")
                StaysOnPhone()
                SectionLabel("While the phone is locked", Modifier.padding(top = 8.dp))
                InboxModeChoice(state.settings.inboxMode, state.settings::updateInboxMode)
                Body("Your notes close when the phone locks, or after ${state.settings.idleMinutes} minutes unused. You can change all of this in Profile › Settings.")
            }
            3 -> Step(3, back, null, "Continue", { step = 4 }) {
                Title("Models for your phone")
                ModelAdviceList(state)
                Body("Downloads can run while you continue. You can also do this later in Profile › Device & models.")
            }
            4 -> Step(4, back, null, null, {}) {
                Title("Bring your memory")
                Body("Start from what you already have, or begin empty.")
                Choice("Start empty", "Begin", "Write your first note, or ask: add to today's note: …", emphasized = true) {
                    exit = WelcomeExit.NOTES
                    step = 5
                }
                Choice("Import", "A folder of Markdown notes", "Links, tags and properties come with them. The folder itself is not changed.") {
                    exit = WelcomeExit.NOTES
                    folderPicker.launch(null)
                }
                Choice("Restore", "An encrypted SeBrain backup", "Needs the backup's passphrase. You restore it in Profile › Backup & restore.") {
                    exit = WelcomeExit.RESTORE
                    step = 5
                }
            }
            else -> Step(5, back, null, "Open SeBrain", { finish(exit) }) { Ready() }
        }
    }
}

@Composable
private fun Splash(onNext: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(SPLASH_MS)
        onNext()
    }
    Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClick = onNext)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "SeBrain logo", modifier = Modifier.size(176.dp))
            Text("SEBRAIN", style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp), color = Neon.Cyan)
        }
        SectionLabel("Private · Local · Yours", Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
    }
}

@Composable
private fun Step(
    number: Int,
    onBack: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    action: String?,
    onAction: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        PageHeader("Step 0$number / 0$STEPS", onBack, trailing)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        if (action != null) PrimaryAction(action, onAction, Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun Intro() {
    Spacer(Modifier.size(24.dp))
    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(80.dp))
    Text(
        "A private memory that thinks with you.",
        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 30.sp, lineHeight = 36.sp),
    )
    Body(
        "Write, capture and connect what matters. An AI that lives only on this phone remembers it, " +
            "answers from it with sources, and keeps it up to date when you ask.",
    )
    Point("01", "Encrypted on this phone, with keys held by its security chip. Nothing leaves unless you send it.")
    Point("02", "The AI runs on the phone's own chips. No account, no cloud, works offline.")
    Point("03", "Notes link, tag and remember dates, so the AI recalls the right ones, cites them, and changes a note only after you approve.")
}

@Composable
private fun StaysOnPhone() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Fact("Your notes, chats and search index", "Encrypted", Neon.Yellow)
        Fact("Analytics, tracking, crash reports", "None", Neon.Yellow)
        Fact("External AI providers", "Off", Neon.Dim)
    }
}

@Composable
private fun Ready() {
    Title("You're ready")
    Body("Four places, one memory.")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Place("Notes", "Write, link with [[ ]], tag with #.")
        Place("Graph", "See how your notes connect, and what sits at the centre.")
        Place("Ask", "Ask about anything you wrote. Answers cite the notes they used.")
        Place("Profile", "Settings, backup, guides and the models on this phone.")
    }
    Column(Modifier.fillMaxWidth().border(1.dp, Neon.Line).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TIP", style = MaterialTheme.typography.labelSmall, color = Neon.Yellow)
        Body("In any app, tap Share › SeBrain to save text or a link to your memory, even with the phone locked.", Neon.Cyan)
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleLarge)

@Composable
private fun Body(text: String, color: androidx.compose.ui.graphics.Color = Neon.Dim) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)

@Composable
private fun Point(number: String, text: String) {
    Row(Modifier.fillMaxWidth().clip(CardCut).background(Neon.Panel).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(number, style = MaterialTheme.typography.labelMedium, color = Neon.Coral)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Fact(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().background(Neon.Panel).padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value.uppercase(), style = MaterialTheme.typography.labelSmall, color = valueColor, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Place(name: String, text: String) {
    Row(Modifier.fillMaxWidth().background(Neon.Panel).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(name.uppercase(), style = MaterialTheme.typography.labelMedium, color = Neon.Coral, modifier = Modifier.width(72.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Choice(kind: String, title: String, text: String, emphasized: Boolean = false, onClick: () -> Unit) {
    val frame = if (emphasized) Modifier.clip(CardCut).background(Neon.PanelHigh) else Modifier.border(1.dp, Neon.Line)
    Column(
        Modifier.fillMaxWidth().then(frame).clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (emphasized) Neon.Coral else Neon.Dim)
        Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Neon.Dim)
    }
}
