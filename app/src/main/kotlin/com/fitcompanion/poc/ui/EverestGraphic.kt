package com.fitcompanion.poc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitcompanion.core.ChallengeProgress
import com.fitcompanion.core.Milestone

/**
 * A mountain with a switchback trail. The climber sits at the height you've climbed in total; the
 * part of the trail you climbed this week is highlighted. Landmarks are marked at their real heights
 * along the right-hand edge.
 */
@Composable
fun MountainGraphic(progress: ChallengeProgress, milestones: List<Milestone>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val rock = Color(0xFF8D9AA6)
    val snow = Color(0xFFF4F7FA)

    Canvas(modifier.fillMaxWidth().height(260.dp)) {
        val w = size.width; val h = size.height
        val baseY = h - 12f; val peakY = 18f
        val left = 8f; val right = w * 0.70f; val peakX = (left + right) / 2
        fun yFor(meters: Double) = (baseY - (meters / progress.goalMeters).toFloat().coerceIn(0f, 1f) * (baseY - peakY))

        // Mountain body and snow cap.
        val mountain = Path().apply {
            moveTo(left, baseY); lineTo(peakX - w * 0.14f, h * 0.42f); lineTo(peakX - w * 0.07f, h * 0.30f)
            lineTo(peakX, peakY); lineTo(peakX + w * 0.09f, h * 0.36f); lineTo(peakX + w * 0.16f, h * 0.48f)
            lineTo(right, baseY); close()
        }
        drawPath(mountain, rock.copy(alpha = 0.45f))
        val snowLine = h * 0.30f
        drawPath(Path().apply {
            moveTo(peakX - w * 0.07f, snowLine); lineTo(peakX, peakY); lineTo(peakX + w * 0.06f, snowLine + 12f)
            lineTo(peakX + 4f, snowLine + 2f); lineTo(peakX - 10f, snowLine + 10f); close()
        }, snow)

        // Switchback trail: points with strictly decreasing y, so a height maps to one spot.
        val zig = 7
        val trail = (0..zig).map { i ->
            val t = i / zig.toFloat()
            val y = baseY - t * (baseY - peakY)
            val halfWidth = (1 - t) * (right - left) * 0.32f
            val x = peakX + if (i == zig) 0f else if (i % 2 == 0) -halfWidth else halfWidth
            Offset(x, y)
        }
        fun pointAt(meters: Double): Offset {
            val y = yFor(meters)
            val i = trail.indexOfFirst { it.y <= y }.coerceAtLeast(1)
            val a = trail[i - 1]; val b = trail[i]
            val f = if (a.y == b.y) 0f else (a.y - y) / (a.y - b.y)
            return Offset(a.x + f * (b.x - a.x), y)
        }
        fun trailPath(fromM: Double, toM: Double) = Path().apply {
            val s = pointAt(fromM); moveTo(s.x, s.y)
            trail.filter { it.y < s.y && it.y > yFor(toM) }.forEach { lineTo(it.x, it.y) }
            val e = pointAt(toM); lineTo(e.x, e.y)
        }

        drawPath(trailPath(0.0, progress.goalMeters), quiet, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
        val total = progress.totalMeters.coerceAtMost(progress.goalMeters)
        val weekStart = (total - progress.thisWeekMeters).coerceAtLeast(0.0)
        drawPath(trailPath(0.0, weekStart), ink.copy(alpha = 0.6f), style = Stroke(4f, cap = StrokeCap.Round))
        drawPath(trailPath(weekStart, total), primary, style = Stroke(7f, cap = StrokeCap.Round))

        // Climber.
        val me = pointAt(total)
        drawCircle(primary, 10f, me)
        drawCircle(Color.White, 4f, me)

        // Landmarks on the right.
        val labelStyle = TextStyle(fontSize = 10.sp, color = quiet)
        val reachedStyle = TextStyle(fontSize = 10.sp, color = ink)
        for (m in milestones) {
            val y = yFor(m.atMeters)
            val reached = m.atMeters <= total
            drawLine(if (reached) primary else quiet, Offset(right - 10f, y), Offset(right + 6f, y), 2f)
            drawText(measurer, "${m.name} · ${"%,.0f".format(m.atMeters)} m", Offset(right + 10f, y - 8f),
                if (reached) reachedStyle else labelStyle)
        }
    }
}
