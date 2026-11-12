package com.sebrain.app.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.sebrain.core.vault.NoteGraph
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val NODE = Neon.Yellow
private val MISSING = Color(0xFF6B6B6B)
private val EDGE = Color(0xAA00F0FF)
private val BACKGROUND = Neon.Void

/** Where each node is drawn, in graph units, and how many links it has. */
private class Layout(val x: FloatArray, val y: FloatArray, val degree: IntArray)

/**
 * Force-directed layout: nodes repel each other, links pull their ends together and a weak
 * pull keeps everything in one disc. Nodes start on a sunflower spiral, so notes without
 * links still fill the disc evenly.
 */
private fun layout(graph: NoteGraph): Layout {
    val n = graph.nodes.size
    val x = FloatArray(n)
    val y = FloatArray(n)
    val degree = IntArray(n)
    graph.edges.forEach { (a, b) -> degree[a]++; degree[b]++ }
    for (i in 0 until n) {
        val r = SPACING * sqrt(i + 0.5f)
        val angle = i * 2.3999631f
        x[i] = r * cos(angle)
        y[i] = r * sin(angle)
    }
    if (n < 2) return Layout(x, y, degree)
    val dx = FloatArray(n)
    val dy = FloatArray(n)
    val iterations = (200_000_000L / (n.toLong() * n)).coerceIn(15, 200).toInt()
    var step = SPACING
    repeat(iterations) {
        dx.fill(0f)
        dy.fill(0f)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val ex = x[i] - x[j]
                val ey = y[i] - y[j]
                val d2 = max(ex * ex + ey * ey, 0.01f)
                if (d2 > REACH * REACH) continue
                val force = SPACING * SPACING / d2
                dx[i] += ex * force; dy[i] += ey * force
                dx[j] -= ex * force; dy[j] -= ey * force
            }
            dx[i] -= x[i] * GRAVITY
            dy[i] -= y[i] * GRAVITY
        }
        graph.edges.forEach { (a, b) ->
            val ex = x[a] - x[b]
            val ey = y[a] - y[b]
            val force = sqrt(ex * ex + ey * ey) / SPACING * PULL
            dx[a] -= ex * force; dy[a] -= ey * force
            dx[b] += ex * force; dy[b] += ey * force
        }
        for (i in 0 until n) {
            val length = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 0.01f)
            val move = min(length, step)
            x[i] += dx[i] / length * move
            y[i] += dy[i] / length * move
        }
        step *= 0.985f
    }
    return Layout(x, y, degree)
}

private const val SPACING = 30f
private const val REACH = SPACING * 8
private const val GRAVITY = 0.02f
private const val PULL = 0.25f

@Composable
fun GraphScreen(state: AppState, modifier: Modifier = Modifier) {
    val graph = state.graph
    var layout by remember { mutableStateOf<Layout?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(Unit) { state.loadGraph() }
    LaunchedEffect(graph) {
        layout = null
        if (graph != null) {
            val computed = withContext(Dispatchers.Default) { layout(graph) }
            // Start zoomed so the whole disc fits the width of a phone.
            val radius = SPACING * sqrt(graph.nodes.size.toFloat()) + SPACING
            scale = (480f / radius).coerceIn(0.15f, 2.5f)
            offset = Offset.Zero
            layout = computed
        }
    }
    val labelPaint = remember {
        Paint().apply { color = Color(0xFFDADADA).toArgb(); textSize = 26f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    }
    Box(modifier.fillMaxSize().background(BACKGROUND)) {
        val placed = layout
        if (graph == null || placed == null) {
            Text("Drawing the graph…", color = Color(0xFF9A9A9A), modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        fun radiusOf(i: Int) = (7f + 2.5f * min(placed.degree[i], 8)) * scale.coerceIn(0.6f, 1.6f)
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(0.1f, 6f)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        // Zoom around the fingers, not around the middle of the screen.
                        offset = (offset + center - centroid) * (newScale / scale) + centroid - center + pan
                        scale = newScale
                    }
                }
                .pointerInput(graph, placed) {
                    detectTapGestures { tap ->
                        val cx = size.width / 2f + offset.x
                        val cy = size.height / 2f + offset.y
                        var best = -1
                        var bestDistance = Float.MAX_VALUE
                        for (i in graph.nodes.indices) {
                            val ex = cx + placed.x[i] * scale - tap.x
                            val ey = cy + placed.y[i] * scale - tap.y
                            val d = ex * ex + ey * ey
                            if (d < bestDistance) { bestDistance = d; best = i }
                        }
                        val reach = if (best >= 0) radiusOf(best) + 24f else 0f
                        if (best >= 0 && bestDistance <= reach * reach) graph.nodes[best].id?.let(state::openFromGraph)
                    }
                },
        ) {
            val cx = size.width / 2f + offset.x
            val cy = size.height / 2f + offset.y
            graph.edges.forEach { (a, b) ->
                drawLine(
                    EDGE,
                    Offset(cx + placed.x[a] * scale, cy + placed.y[a] * scale),
                    Offset(cx + placed.x[b] * scale, cy + placed.y[b] * scale),
                    strokeWidth = 1.5f,
                )
            }
            val labels = scale >= 1.4f || graph.nodes.size <= 40
            for (i in graph.nodes.indices) {
                val px = cx + placed.x[i] * scale
                val py = cy + placed.y[i] * scale
                if (px < -40f || py < -40f || px > size.width + 40f || py > size.height + 40f) continue
                val radius = radiusOf(i)
                drawCircle(if (graph.nodes[i].id == null) MISSING else NODE, radius, Offset(px, py))
                if (labels) drawContext.canvas.nativeCanvas.drawText(graph.nodes[i].title.take(24), px, py + radius + 28f, labelPaint)
            }
        }
        Text(
            "${graph.nodes.count { it.id != null }} notes · ${graph.edges.size} links",
            color = Color(0xFF9A9A9A),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        )
    }
}
