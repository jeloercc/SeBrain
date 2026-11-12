package com.sebrain.app

import android.os.Bundle
import android.view.WindowManager
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.sebrain.app.ui.AppState
import com.sebrain.app.ui.ChatScreen
import com.sebrain.app.ui.GraphScreen
import com.sebrain.app.ui.ModelsScreen
import com.sebrain.app.ui.NotesScreen
import com.sebrain.app.ui.SeBrainTheme

/** The whole app: notes, the local assistant and models. Its content is excluded from screenshots. */
class MainActivity : ComponentActivity() {
    private val state by lazy { AppState(application as SeBrainApp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            SeBrainTheme {
                val tab = state.tab
                // The editor and the note view take the whole screen.
                val fullScreen = tab == 0 && (state.draft != null || state.detail != null)
                Scaffold(
                    bottomBar = {
                        if (!fullScreen) {
                            NavigationBar {
                                NavigationBarItem(tab == 0, { state.tab = 0 }, { Icon(Icons.Filled.Edit, null) }, label = { Text("Notes") })
                                NavigationBarItem(tab == 1, { state.tab = 1 }, { Icon(Icons.Filled.Share, null) }, label = { Text("Graph") })
                                NavigationBarItem(tab == 2, { state.tab = 2 }, { Icon(Icons.Filled.Face, null) }, label = { Text("Ask") })
                                NavigationBarItem(tab == 3, { state.tab = 3 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Models") })
                            }
                        }
                    },
                ) { padding ->
                    when (tab) {
                        0 -> if (fullScreen) NotesScreen(state) else NotesScreen(state, Modifier.padding(padding))
                        1 -> GraphScreen(state, Modifier.padding(padding))
                        2 -> ChatScreen(state, Modifier.padding(padding).consumeWindowInsets(padding))
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
