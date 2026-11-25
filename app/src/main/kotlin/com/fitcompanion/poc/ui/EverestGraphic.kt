package com.fitcompanion.poc.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitcompanion.core.ChallengeProgress
import com.fitcompanion.core.Milestone
import com.fitcompanion.poc.ui.theme.Inter
import com.fitcompanion.poc.ui.theme.Palette

/**
 * Night-sky mountain with a switchback trail. Your climber sits at your total height (animated);
 * this week's climb glows in volt. Landmarks are marked at their real heights on the right.
 */
@Composable
fun MountainGraphic(progress: ChallengeProgress, milestones: List<Milestone>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val total by animateFloatAsState(progress.totalMeters.coerceAtMost(progress.goalMeters).toFloat(), tween(1200), label = "climb")
    val weekStartTarget = (progress.totalMeters.coerceAtMost(progress.goalMeters) - progress.thisWeekMeters).coerceAtLeast(0.0).toFloat()
    val weekStart by animateFloatAsState(weekStartTarget, tween(1200), label = "week")

    Canvas(modifier.fillMaxWidth().height(300.dp)) {
        val w = size.width; val h = size.height
        val baseY = h - 6f; val peakY = 26f
        val left = 0f; val right = w * 0.66f; val peakX = right * 0.5f
        val goal = progress.goalMeters.toFloat()
        fun yFor(m: Float) = baseY - (m / goal).coerceIn(0f, 1f) * (baseY - peakY)

        // Sky glow behind the peak.
        drawCircle(Brush.radialGradient(listOf(Palette.Climb.copy(alpha = 0.35f), Color.Transparent), Offset(peakX, peakY + 40f), w * 0.45f),
            w * 0.45f, Offset(peakX, peakY + 40f))

        // Back ridge, then main mountain.
        drawPath(Path().apply {
            moveTo(left, baseY); lineTo(w * 0.12f, h * 0.55f); lineTo(w * 0.22f, h * 0.62f); lineTo(peakX + w * 0.2f, h * 0.45f)
            lineTo(right + w * 0.12f, h * 0.7f); lineTo(right + w * 0.12f, baseY); close()
        }, Palette.SurfaceHigh)
        val mountain = Path().apply {
            moveTo(left, baseY); lineTo(peakX - w * 0.17f, h * 0.44f); lineTo(peakX - w * 0.08f, h * 0.32f)
            lineTo(peakX, peakY); lineTo(peakX + w * 0.1f, h * 0.36f); lineTo(peakX + w * 0.18f, h * 0.5f)
            lineTo(right, baseY); close()
        }
        drawPath(mountain, Brush.verticalGradient(listOf(Color(0xFF3A3F63), Color(0xFF1A1F2C)), peakY, baseY))
        drawPath(Path().apply {
            moveTo(peakX - w * 0.08f, h * 0.32f); lineTo(peakX, peakY); lineTo(peakX + w * 0.07f, h * 0.33f)
            lineTo(peakX + 6f, h * 0.30f); lineTo(peakX - 12f, h * 0.35f); close()
        }, Color(0xFFE8EEF7))

        // Switchback trail: strictly rising, so any height maps to exactly one point.
        val zig = 8
        val trail = (0..zig).map { i ->
            val t = i / zig.toFloat()
            val y = baseY - t * (baseY - peakY)
            val half = (1 - t) * (right - left) * 0.34f
            Offset(peakX + if (i == zig) 0f else if (i % 2 == 0) -half else half, y)
        }
        fun pointAt(m: Float): Offset {
            val y = yFor(m)
            val i = trail.indexOfFirst { it.y <= y }.coerceAtLeast(1)
            val a = trail[i - 1]; val b = trail[i]
            val f = if (a.y == b.y) 0f else (a.y - y) / (a.y - b.y)
            return Offset(a.x + f * (b.x - a.x), y)
        }
        fun segment(fromM: Float, toM: Float) = Path().apply {
            val s = pointAt(fromM); moveTo(s.x, s.y)
            trail.filter { it.y < s.y && it.y > yFor(toM) }.forEach { lineTo(it.x, it.y) }
            val e = pointAt(toM); lineTo(e.x, e.y)
        }

        drawPath(segment(0f, goal), Palette.TextDim.copy(alpha = 0.6f), style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 9f))))
        drawPath(segment(0f, weekStart), Color.White.copy(alpha = 0.75f), style = Stroke(5f, cap = StrokeCap.Round))
        // This week: glow then bright core.
        drawPath(segment(weekStart, total), Palette.Volt.copy(alpha = 0.25f), style = Stroke(18f, cap = StrokeCap.Round))
        drawPath(segment(weekStart, total), Palette.Volt, style = Stroke(7f, cap = StrokeCap.Round))

        // Summit flag.
        val top = trail.last()
        drawLine(Color.White, top, Offset(top.x, top.y - 22f), 2.5f)
        drawPath(Path().apply { moveTo(top.x, top.y - 22f); lineTo(top.x + 16f, top.y - 17f); lineTo(top.x, top.y - 12f); close() }, Palette.Run)

        // Climber.
        val me = pointAt(total)
        drawCircle(Palette.Volt.copy(alpha = 0.25f), 22f, me)
        drawCircle(Palette.Volt, 11f, me)
        drawCircle(Palette.Bg, 4.5f, me)

        // Landmarks.
        val dim = TextStyle(fontFamily = Inter, fontSize = 10.sp, color = Palette.TextDim)
        val lit = TextStyle(fontFamily = Inter, fontSize = 10.sp, color = Palette.Text, fontWeight = FontWeight.SemiBold)
        var lastLabelY = Float.MAX_VALUE
        for (m in milestones.sortedBy { it.atMeters }) {
            val y = yFor(m.atMeters.toFloat())
            if (lastLabelY - y < 24f) continue // avoid overlapping labels
            lastLabelY = y
            val reached = m.atMeters <= progress.totalMeters
            drawLine(if (reached) Palette.Volt else Palette.Outline, Offset(right - 4f, y), Offset(right + 14f, y), 2f)
            drawText(measurer, m.name, Offset(right + 20f, y - 14f), if (reached) lit else dim)
            drawText(measurer, "%,.0f m".format(m.atMeters), Offset(right + 20f, y), dim)
        }
    }
}
