package com.sebrain.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared pieces of the post-punk look (docs/design-postpunk.md): cut corners, mono labels. */

val CardCut = CutCornerShape(topEnd = 12.dp)
private val ActionCut = CutCornerShape(topEnd = 14.dp, bottomStart = 14.dp)

/** The one main action of a screen: a coral bar with a mono label. */
@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(ActionCut)
            .background(if (enabled) Palette.Coral else Palette.PanelHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
            color = if (enabled) Palette.Ink else Palette.Dim,
        )
    }
}

/** A quiet text action, such as BACK, SKIP or LATER. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = Palette.Coral) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Palette.Dim, modifier = modifier)
}

/** A row that opens another page: title, a short line under it, and a chevron. */
@Composable
fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(Palette.Panel)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = Palette.Bone)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.Dim)
        }
        Text(">", style = MaterialTheme.typography.labelLarge, color = Palette.Dim, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A small status tag such as NPU, GPU, ENCRYPTED or PENDING DECISION. */
@Composable
fun Tag(text: String, filled: Color? = null) {
    val base = Modifier.padding(0.dp)
    val styled = if (filled != null) base.background(filled) else base.border(1.dp, Palette.Line)
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = if (filled != null) Palette.Ink else Palette.Dim,
        modifier = styled.padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** BACK on the left and a mono caption on the right, at the top of a page or a welcome step. */
@Composable
fun PageHeader(caption: String, onBack: (() -> Unit)?, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        if (onBack != null) TextAction("Back", onBack) else Box(Modifier)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionLabel(caption)
            trailing?.invoke()
        }
    }
}
