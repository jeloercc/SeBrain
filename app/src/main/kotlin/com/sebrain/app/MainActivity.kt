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
import com.sebrain.app.ui.AppState
import com.sebrain.app.ui.ChatScreen
import com.sebrain.app.ui.GraphScreen
import com.sebrain.app.ui.IntegrationsScreen
import com.sebrain.app.ui.ModelsScreen
import com.sebrain.app.ui.Neon
import com.sebrain.app.ui.NotesScreen
import com.sebrain.app.ui.SeBrainTheme

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
            SeBrainTheme {
                val tab = state.tab
                val fullScreen = tab == 0 && (state.draft != null || state.detail != null)
                Scaffold(
                    bottomBar = {
                        if (!fullScreen) PostPunkNavigation(tab) { state.tab = it }
                    },
                ) { padding ->
                    when (tab) {
                        0 -> if (fullScreen) NotesScreen(state) else NotesScreen(state, Modifier.padding(padding))
                        1 -> GraphScreen(state, Modifier.padding(padding))
                        2 -> ChatScreen(state, Modifier.padding(padding).consumeWindowInsets(padding))
                        3 -> ModelsScreen(state, Modifier.padding(padding))
                        else -> IntegrationsScreen(state, Modifier.padding(padding))
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

    @Composable
    private fun PostPunkNavigation(selected: Int, onSelected: (Int) -> Unit) {
        NavigationBar(containerColor = Neon.Void, tonalElevation = 0.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("NOTES", "GRAPH", "ASK", "MODELS", "INTEGRATE").forEachIndexed { index, label ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clickable { onSelected(index) }
                            .drawBehind {
                                if (index == selected) {
                                    drawRect(
                                        Neon.Coral,
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
                            color = if (index == selected) Neon.Coral else Neon.Dim,
                        )
                    }
                }
            }
        }
    }
}
