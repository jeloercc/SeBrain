package com.sebrain.app

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import com.sebrain.app.ui.AppState
import com.sebrain.app.ui.BrainBar
import com.sebrain.app.ui.ChatScreen
import com.sebrain.app.ui.GraphScreen
import com.sebrain.app.ui.ModelsScreen
import com.sebrain.app.ui.Palette
import com.sebrain.app.ui.NotesScreen
import com.sebrain.app.ui.NewNoteScreen
import com.sebrain.app.ui.ProfilePage
import com.sebrain.app.ui.PrivateInput
import com.sebrain.app.ui.ProfileScreen
import com.sebrain.app.ui.ProposalScreen
import com.sebrain.app.ui.SeBrainTheme
import com.sebrain.app.ui.WelcomeExit
import com.sebrain.app.ui.WelcomeFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** The whole app: notes, the local assistant and models. */
class MainActivity : ComponentActivity() {
    private val state by lazy { AppState(application as SeBrainApp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            // Every text field and selection below: no keyboard learning, copies marked sensitive.
            PrivateInput { SeBrainTheme {
                var profilePage by rememberSaveable { mutableStateOf<ProfilePage?>(null) }
                // First run: the logo and the welcome steps, then the tabs.
                if (!state.settings.welcomeSeen) {
                    WelcomeFlow(state) { exit ->
                        state.tab = if (exit == WelcomeExit.RESTORE) PROFILE_TAB else 0
                        if (exit == WelcomeExit.RESTORE) profilePage = ProfilePage.BACKUP
                    }
                    return@SeBrainTheme
                }
                val tab = state.tab
                val fullScreen = tab == 0 && (state.draft != null || state.detail != null)
                Scaffold(
                    bottomBar = {
                        if (!fullScreen) PostPunkNavigation(tab) { state.tab = it }
                    },
                ) { padding ->
                    when (tab) {
                        0 -> if (fullScreen) {
                            NotesScreen(state)
                        } else {
                            Column(Modifier.padding(padding)) {
                                // The brain the tab is scoped to; the same choice on Notes, Graph and Ask.
                                BrainBar(state, Modifier.padding(top = 8.dp))
                                NotesScreen(state, Modifier.weight(1f))
                            }
                        }
                        1 -> Column(Modifier.padding(padding)) {
                            BrainBar(state, Modifier.padding(vertical = 6.dp))
                            GraphScreen(state, Modifier.weight(1f))
                        }
                        2 -> Column(Modifier.padding(padding).consumeWindowInsets(padding)) {
                            BrainBar(state, Modifier.padding(top = 6.dp))
                            ChatScreen(state, Modifier.weight(1f))
                        }
                        3 -> ModelsScreen(state, Modifier.padding(padding))
                        else -> ProfileScreen(state, profilePage, { profilePage = it }, Modifier.padding(padding))
                    }
                }
                // A change the assistant proposes to a note is reviewed over whatever tab is open.
                state.proposal?.let { ProposalScreen(state, it) }
                // A note written in the chat waits here until it is saved or discarded; one review at a time.
                if (state.proposal == null) state.newNote?.let { NewNoteScreen(state, it) }
            } }
        }
    }

    private companion object {
        const val PROFILE_TAB = 4
    }

    override fun onStart() {
        super.onStart()
        state.start()
    }

    override fun onStop() {
        state.stop()
        super.onStop()
    }

    @Composable
    private fun PostPunkNavigation(selected: Int, onSelected: (Int) -> Unit) {
        NavigationBar(containerColor = Palette.Ink, tonalElevation = 0.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("NOTES", "GRAPH", "ASK", "MODELS", "PROFILE").forEachIndexed { index, label ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clickable { onSelected(index) }
                            .drawBehind {
                                if (index == selected) {
                                    drawRect(
                                        Palette.Coral,
                                        topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.25f, size.height - 3.dp.toPx()),
                                        size = androidx.compose.ui.geometry.Size(size.width * 0.5f, 3.dp.toPx()),
                                    )
                                }
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (index == selected) Palette.Coral else Palette.Dim,
                        )
                    }
                }
            }
        }
    }
}
