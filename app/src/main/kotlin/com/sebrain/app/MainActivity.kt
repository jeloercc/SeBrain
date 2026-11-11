package com.sebrain.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sebrain.app.ui.AppState
import com.sebrain.app.ui.ChatScreen
import com.sebrain.app.ui.ModelsScreen
import com.sebrain.app.ui.NotesScreen
import com.sebrain.app.ui.SeBrainTheme

/** The whole app: notes, the local assistant and models. Its content is excluded from screenshots. */
class MainActivity : ComponentActivity() {
    private val state by lazy { AppState(application as SeBrainApp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            SeBrainTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                // The editor and the note view take the whole screen.
                val fullScreen = tab == 0 && (state.draft != null || state.detail != null)
                Scaffold(
                    bottomBar = {
                        if (!fullScreen) {
                            NavigationBar {
                                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Edit, null) }, label = { Text("Notes") })
                                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Face, null) }, label = { Text("Ask") })
                                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Models") })
                            }
                        }
                    },
                ) { padding ->
                    when (tab) {
                        0 -> if (fullScreen) NotesScreen(state) else NotesScreen(state, Modifier.padding(padding))
                        1 -> ChatScreen(state, Modifier.padding(padding))
                        else -> ModelsScreen(state, Modifier.padding(padding))
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        state.start()
    }

    override fun onStop() {
        state.stop()
        super.onStop()
    }
}
