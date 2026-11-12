package com.sebrain.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal class Segment(val code: Boolean, val text: String, val language: String)

private val WIKI_LINK = Regex("""\[\[([^\]]+)]]""")

/** Splits Markdown into prose and fenced code blocks. An unclosed fence runs to the end. */
internal fun segments(text: String): List<Segment> {
    val out = ArrayList<Segment>()
    text.split("```").forEachIndexed { i, part ->
        if (i % 2 == 0) {
            if (part.isNotBlank()) out += Segment(false, part.trim('\n'), "")
        } else {
            val newline = part.indexOf('\n')
            val first = if (newline >= 0) part.substring(0, newline).trim() else ""
            val isLanguage = first.isNotEmpty() && first.length <= 20 && first.none { it.isWhitespace() }
            val body = if (isLanguage) part.substring(newline + 1) else part
            if (body.isNotBlank()) out += Segment(true, body.trim('\n'), if (isLanguage) first.lowercase() else "")
        }
    }
    return out
}

private val EXTENSIONS = mapOf(
    "bash" to "sh", "sh" to "sh", "shell" to "sh", "zsh" to "sh", "python" to "py", "py" to "py",
    "javascript" to "js", "js" to "js", "typescript" to "ts", "ts" to "ts", "kotlin" to "kt", "java" to "java",
    "c" to "c", "cpp" to "cpp", "c++" to "cpp", "rust" to "rs", "go" to "go", "ruby" to "rb", "php" to "php",
    "lua" to "lua", "perl" to "pl", "sql" to "sql", "json" to "json", "yaml" to "yml", "yml" to "yml",
    "html" to "html", "css" to "css", "markdown" to "md", "md" to "md",
)

internal fun fileName(title: String, language: String): String {
    val stem = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40).ifEmpty { "script" }
    return "$stem.${EXTENSIONS[language] ?: "txt"}"
}

/** Hands [text] to Termux, which offers to save it under ~/downloads. Falls back to the share sheet. */
internal fun sendToTermux(context: Context, name: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(TERMUX)
        .putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent.createChooser(intent.setPackage(null), null))
    }
}

private fun copy(context: Context, text: String) {
    val clip = ClipData.newPlainText("code", text)
    // Keeps the content out of the clipboard preview.
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private const val TERMUX = "com.termux"

/** Shows Markdown text with its code blocks set apart. [title] names the file a block is sent as. */
@Composable
fun MarkdownBody(
    text: String,
    title: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onLinkClick: (String) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments(text).forEach { segment ->
            if (segment.code) {
                CodeBlock(segment, title)
            } else {
                InlineMarkdown(segment.text, color = color, onLinkClick = onLinkClick)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InlineMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
    onLinkClick: (String) -> Unit = {},
) {
    val parts = buildList {
        var cursor = 0
        WIKI_LINK.findAll(text).forEach { match ->
            if (match.range.first > cursor) add(text.substring(cursor, match.range.first) to null)
            add(match.groupValues[1] to match.groupValues[1])
            cursor = match.range.last + 1
        }
        if (cursor < text.length) add(text.substring(cursor) to null)
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxLines = maxLines,
    ) {
        parts.forEach { (value, link) ->
            if (link == null) {
                SelectionContainer {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = maxLines)
                }
            } else {
                LinkPill(link, onClick = { onLinkClick(link) })
            }
        }
    }
}

@Composable
fun LinkPill(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .background(Neon.PanelHigh, MaterialTheme.shapes.small)
            .border(BorderStroke(1.dp, Neon.Line), MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Neon.Cyan, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CodeBlock(segment: Segment, title: String) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val name = fileName(title, segment.language)
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF05050A), MaterialTheme.shapes.small)
            .border(BorderStroke(1.dp, Neon.Line), MaterialTheme.shapes.small),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                segment.language.ifEmpty { "code" }.uppercase(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = Neon.Yellow,
            )
            TextButton(onClick = { copy(context, segment.text) }) { Text("COPY") }
            TextButton(onClick = { confirm = true }) { Text("TERMUX", color = Neon.Magenta) }
        }
        SelectionContainer {
            Text(
                segment.text,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color(0xFFB8FFF2),
            )
        }
    }
    if (confirm) {
        Confirm(
            "Send to Termux?",
            "This code leaves the vault, unencrypted. Termux will offer to save it as $name in ~/downloads.",
            "Send",
            onDismiss = { confirm = false },
        ) { sendToTermux(context, name, segment.text) }
    }
}
