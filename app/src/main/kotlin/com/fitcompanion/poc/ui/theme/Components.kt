package com.fitcompanion.poc.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitcompanion.core.DataQuality

val CardShape = RoundedCornerShape(24.dp)
val PillShape = RoundedCornerShape(50)

/** Base card: flat dark surface with a hairline border. */
@Composable
fun SportCard(
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    padding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.fillMaxWidth().clip(CardShape)
        .let { if (brush != null) it.background(brush) else it.background(Palette.Surface) }
        .border(1.dp, Palette.Outline.copy(alpha = if (brush != null) 0f else 1f), CardShape)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(padding)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Palette.Text) {
        Column(base, verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Small all-caps label, as on a race bib. */
@Composable
fun Eyebrow(text: String, color: Color = Palette.TextDim, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            subtitle?.let { Eyebrow(it) }
            Text(title.uppercase(), style = MaterialTheme.typography.headlineLarge, color = Palette.Text)
        }
        trailing()
    }
}

@Composable
fun SectionHeader(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = Palette.Volt, modifier = Modifier.clickable(onClick = onAction).padding(4.dp))
        }
    }
}

/** Big number + unit, with a quality marker so estimates and gaps are never mistaken for measurements. */
@Composable
fun BigStat(value: String?, unit: String, quality: DataQuality = DataQuality.MEASURED, color: Color = Palette.Text, big: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            value ?: "—",
            style = if (big) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall,
            color = if (value == null) Palette.TextDim else color,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Text(unit, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, modifier = Modifier.padding(bottom = if (big) 8.dp else 5.dp))
        if (value != null && quality == DataQuality.ESTIMATED) {
            Spacer(Modifier.width(6.dp))
            Tag("EST", Palette.Warn, Modifier.padding(bottom = if (big) 10.dp else 6.dp))
        }
    }
}

@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = modifier.clip(PillShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Compact tile: icon chip, label, value. Used in grids. */
@Composable
fun StatTile(
    icon: ImageVector, label: String, value: String?, unit: String, accent: Color,
    modifier: Modifier = Modifier, footnote: String? = null, quality: DataQuality = DataQuality.MEASURED,
) {
    Column(
        modifier.clip(CardShape).background(Palette.Surface).border(1.dp, Palette.Outline, CardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(8.dp))
            Eyebrow(label)
        }
        BigStat(value, unit, quality)
        footnote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Animated progress ring with a gradient sweep. */
@Composable
fun ProgressRing(fraction: Float, size: Dp, stroke: Dp, brush: Brush, track: Color = Palette.SurfaceHigh, content: @Composable () -> Unit = {}) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(900), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            val arcSize = Size(this.size.width - s, this.size.height - s)
            val tl = Offset(s / 2, s / 2)
            drawArc(track, -90f, 360f, false, tl, arcSize, style = Stroke(s))
            if (f > 0f) drawArc(brush, -90f, 360f * f, false, tl, arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Thick rounded progress bar with gradient fill. */
@Composable
fun ProgressBar(fraction: Float, brush: Brush, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(900), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(PillShape).background(Palette.SurfaceHigh)) {
        Box(Modifier.fillMaxWidth(f).height(height).clip(PillShape).background(brush))
    }
}

/** Primary call to action: volt pill. */
@Composable
fun VoltButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Row(
        modifier.clip(PillShape).background(if (enabled) Palette.Volt else Palette.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = if (enabled) Palette.Bg else Palette.TextDim, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (enabled) Palette.Bg else Palette.TextDim, maxLines = 1, softWrap = false)
    }
}

/** Secondary action: outlined pill. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, color: Color = Palette.Text) {
    Row(
        modifier.clip(PillShape).border(1.dp, Palette.Outline, PillShape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = if (enabled) color else Palette.TextDim, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (enabled) color else Palette.TextDim, maxLines = 1, softWrap = false)
    }
}

/** Selectable chip. */
@Composable
fun ChoiceChip(
    text: String, selected: Boolean, onClick: () -> Unit, accent: Color = Palette.Volt,
    selectedText: Color = Palette.Bg, unselectedBg: Color = Palette.SurfaceHigh, unselectedText: Color = Palette.Text,
) {
    Text(
        if (selected) "✓ $text" else text, style = MaterialTheme.typography.labelLarge,
        color = if (selected) selectedText else unselectedText,
        modifier = Modifier.clip(PillShape)
            .background(if (selected) accent else unselectedBg)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** Hazard-striped banner shown whenever demo data is on screen. */
@Composable
fun DemoBanner(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Palette.Warn, Color(0xFFFF9F1C))))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text("DEMO DATA · not your real Fitbit results", style = MaterialTheme.typography.labelMedium, color = Palette.Bg)
    }
}

/** Status line: sync time or a message. */
@Composable
fun StatusLine(text: String, color: Color = Palette.TextDim) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
}
