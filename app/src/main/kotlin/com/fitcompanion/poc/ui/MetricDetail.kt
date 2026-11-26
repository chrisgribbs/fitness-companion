package com.fitcompanion.poc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fitcompanion.core.ActivityRecord
import com.fitcompanion.core.DailyMetric
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.ui.theme.BigStat
import com.fitcompanion.poc.ui.theme.ChoiceChip
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.SportCard
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

enum class MetricKind { STEPS, SLEEP, RESTING_HR, HRV, CLIMB }

private data class MetricSpec(
    val title: String, val unit: String, val icon: ImageVector, val color: Color,
    val value: (DailyMetric) -> Double?, val format: (Double) -> String, val about: String, val lowerIsBetter: Boolean? = null,
)

private fun spec(kind: MetricKind): MetricSpec = when (kind) {
    MetricKind.STEPS -> MetricSpec("Steps", "steps", Icons.Rounded.Hiking, Palette.Volt, { it.steps?.toDouble() },
        { "%,.0f".format(it) }, "All the steps your Fitbit counted that day, from walks, runs and moving around.")
    MetricKind.SLEEP -> MetricSpec("Sleep", "h", Icons.Rounded.Bedtime, Palette.Sleep, { it.sleepMinutes?.toDouble() },
        { m -> "${m.roundToInt() / 60}:%02d".format(m.roundToInt() % 60) },
        "Time asleep for the night ending on that date (awake time in bed isn't counted). Most adults are advised to get 7–9 hours.")
    MetricKind.RESTING_HR -> MetricSpec("Resting heart rate", "bpm", Icons.Rounded.Favorite, Palette.Heart, { it.restingHeartRate?.toDouble() },
        { "%.0f".format(it) },
        "Your heart rate while fully at rest, measured by your Fitbit, mostly overnight. A lower number over weeks usually goes with better fitness; " +
            "a jump of several beats above your usual can follow hard training, poor sleep, alcohol or getting sick.", lowerIsBetter = true)
    MetricKind.HRV -> MetricSpec("Heart rate variability", "ms", Icons.Rounded.MonitorHeart, Palette.Climb, { it.hrvMillis },
        { "%.0f".format(it) },
        "The tiny variation between heartbeats while you sleep. It's very personal, so compare it only with your own usual. " +
            "Higher than your usual generally means well recovered; a drop can mean stress, a hard session or not enough sleep.", lowerIsBetter = false)
    MetricKind.CLIMB -> MetricSpec("Climb", "m", Icons.Rounded.Terrain, Palette.Volt, { null }, { "%.0f".format(it) },
        "Height gained during runs and walks, smoothed to remove GPS noise. It's always an estimate.")
}

/** Full-screen details for one of the Today tiles. Every number here is calculated in code. */
@Composable
fun MetricDetail(app: AppController, kind: MetricKind, onClose: () -> Unit) {
    val s = spec(kind)
    var days by remember { mutableIntStateOf(30) }
    val today = LocalDate.now(app.zone)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(s.color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(s.icon, null, tint = s.color)
                }
                Spacer(Modifier.width(12.dp))
                Text(s.title.uppercase(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Palette.Text) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90).forEach { d ->
                    val label = if (kind == MetricKind.CLIMB) when (d) { 7 -> "4 weeks"; 30 -> "12 weeks"; else -> "26 weeks" } else "$d days"
                    ChoiceChip(label, days == d, { days = d })
                }
            }
            if (kind == MetricKind.CLIMB) ClimbDetail(app, s, when (days) { 7 -> 4; 30 -> 12; else -> 26 }, today)
            else DailyDetail(app, s, days, today)
            Text(s.about, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            Text("Source: your Fitbit via ${if (app.cloudEnabled) "Google Health and Health Connect" else "Health Connect"}. Measured values, nothing estimated or filled in; days without data are left blank.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DailyDetail(app: AppController, s: MetricSpec, days: Int, today: LocalDate) {
    val from = today.minusDays(days - 1L)
    val byDate = app.daily.associateBy { it.date }
    val series = (0 until days).map { from.plusDays(it.toLong()) }.map { it to byDate[it]?.let(s.value) }
    val values = series.mapNotNull { it.second }
    val latest = app.daily.sortedByDescending { it.date }.firstOrNull { s.value(it) != null }

    SportCard {
        Eyebrow("Latest")
        if (latest != null) {
            BigStat(s.format(s.value(latest)!!), s.unit, color = s.color)
            Text(latest.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        } else Text("No ${s.title.lowercase()} data yet.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
    }
    if (values.isEmpty()) {
        Text("No ${s.title.lowercase()} recorded in the last $days days. If your Fitbit shows it, check Setup → sync status.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Warn)
        return
    }
    val avg = values.average()
    SportCard {
        Eyebrow("Last $days days · average ${s.format(avg)} ${s.unit}")
        BarChart(series.map { it.second }, avg, s.color, Modifier.fillMaxWidth().height(160.dp))
        Row {
            Text(from.format(DateTimeFormatter.ofPattern("d MMM")), style = MaterialTheme.typography.labelSmall, color = Palette.TextDim, modifier = Modifier.weight(1f))
            Text("Today", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            MiniStat("Average", s.format(avg))
            MiniStat("Lowest", s.format(values.min()))
            MiniStat("Highest", s.format(values.max()))
            MiniStat("Days", "${values.size}/$days")
        }
    }
    // Last 7 days vs the 30 days before them, worked out here.
    val last7 = app.daily.filter { !it.date.isBefore(today.minusDays(6)) }.mapNotNull(s.value)
    val base = app.daily.filter { it.date.isBefore(today.minusDays(6)) && !it.date.isBefore(today.minusDays(36)) }.mapNotNull(s.value)
    if (last7.size >= 3 && base.size >= 7) {
        val diff = last7.average() - base.average()
        val pct = if (base.average() != 0.0) diff / base.average() * 100 else 0.0
        val direction = if (kotlin.math.abs(pct) < 3) "about the same as" else if (diff > 0) "higher than" else "lower than"
        val good = when {
            kotlin.math.abs(pct) < 3 || s.lowerIsBetter == null -> null
            s.lowerIsBetter -> diff < 0
            else -> diff > 0
        }
        SportCard {
            Eyebrow("This week vs your usual")
            Text("Your last 7 days average ${s.format(last7.average())} ${s.unit}, $direction your previous 30 days (${s.format(base.average())} ${s.unit}" +
                if (kotlin.math.abs(pct) >= 3) ", ${"%+.0f".format(pct)}%)." else ").",
                style = MaterialTheme.typography.bodyMedium, color = when (good) { true -> Palette.Good; false -> Palette.Warn; null -> Palette.Text })
        }
    }
    SportCard {
        Eyebrow("Day by day")
        series.reversed().take(14).forEach { (d, v) ->
            Row {
                Text(if (d == today) "Today" else d.format(DateTimeFormatter.ofPattern("EEE d MMM")), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(v?.let { "${s.format(it)} ${s.unit}" } ?: "no data", style = MaterialTheme.typography.bodyMedium, color = if (v == null) Palette.TextDim else Palette.Text)
            }
        }
    }
}

@Composable
private fun ClimbDetail(app: AppController, s: MetricSpec, weeks: Int, today: LocalDate) {
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val starts = (weeks - 1 downTo 0).map { monday.minusWeeks(it.toLong()) }
    fun weekOf(a: ActivityRecord) = a.start.atZone(app.zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val inRange = app.activities.filter { weekOf(it) in starts }
    val withClimb = inRange.filter { it.ascentMeters != null }
    val perWeek = starts.map { w -> inRange.filter { weekOf(it) == w }.mapNotNull { it.ascentMeters }.takeIf { it.isNotEmpty() }?.sum() }
    val thisWeek = perWeek.last()

    SportCard {
        Eyebrow("This week")
        BigStat(thisWeek?.let { s.format(it) } ?: "—", s.unit, color = s.color)
        Text("${inRange.count { weekOf(it) == monday }} activities this week, ${withClimb.count { weekOf(it) == monday }} with climb data",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
    if (withClimb.isEmpty()) {
        SportCard {
            Eyebrow("Why there's no climb yet", Palette.Warn)
            Text("None of your ${inRange.size} runs and walks in this period came with elevation data, so there's nothing to add up. " +
                "The app won't guess a number.", style = MaterialTheme.typography.bodyMedium)
            Text("Fitbit usually doesn't pass climb to Health Connect. It's included when an activity has GPS elevation, " +
                "for example through the Google Health account connection in Setup.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        return
    }
    val avg = perWeek.filterNotNull().average()
    SportCard {
        Eyebrow("Last $weeks weeks · weekly average ${s.format(avg)} m")
        BarChart(perWeek, avg, s.color, Modifier.fillMaxWidth().height(160.dp))
        Text("${withClimb.size} of ${inRange.size} activities had climb data. Climb is smoothed and always an estimate.",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** Simple bar chart: gaps for missing days, dashed line at the average. */
@Composable
private fun BarChart(values: List<Double?>, average: Double, color: Color, modifier: Modifier) {
    val max = (values.filterNotNull().maxOrNull() ?: 1.0).coerceAtLeast(average) * 1.1
    val min = 0.0
    Canvas(modifier) {
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val barW = (slot * 0.7f).coerceAtLeast(2f)
        values.forEachIndexed { i, v ->
            if (v == null) {
                drawRect(Palette.Outline, Offset(i * slot + (slot - barW) / 2, size.height - 3f), Size(barW, 3f))
            } else {
                val h = ((v - min) / (max - min) * size.height).toFloat().coerceAtLeast(3f)
                drawRoundRect(if (i == values.lastIndex) color else color.copy(alpha = 0.6f),
                    Offset(i * slot + (slot - barW) / 2, size.height - h), Size(barW, h), CornerRadius(barW / 3, barW / 3))
            }
        }
        val y = size.height - ((average - min) / (max - min) * size.height).toFloat()
        drawLine(Palette.Text.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    }
}
