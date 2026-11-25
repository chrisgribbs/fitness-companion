package com.fitcompanion.poc.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.fitcompanion.core.ActivityKind
import com.fitcompanion.core.ActivityRecord
import com.fitcompanion.poc.ui.theme.Palette
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun meters(m: Double): String = if (m < 10_000) "%,.0f m".format(m) else "%,.1f km".format(m / 1000)

/** "12.4" — kilometres as a bare number for big stats. */
fun kmNumber(m: Double): String = if (m < 100_000) "%.1f".format(m / 1000) else "%,.0f".format(m / 1000)

fun duration(d: Duration): String {
    val total = d.seconds
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun paceText(a: ActivityRecord): String? {
    val d = a.distanceMeters ?: return null
    if (d < 100) return null
    val secPerKm = Duration.between(a.start, a.end).seconds / (d / 1000)
    val s = Math.round(secPerKm)
    return "%d:%02d".format(s / 60, s % 60)
}

fun ActivityKind.label() = when (this) { ActivityKind.RUN -> "Run"; ActivityKind.WALK -> "Walk"; ActivityKind.OTHER -> "Workout" }
fun ActivityKind.color(): Color = when (this) { ActivityKind.RUN -> Palette.Run; ActivityKind.WALK -> Palette.Walk; ActivityKind.OTHER -> Palette.Climb }
fun ActivityKind.icon(): ImageVector = when (this) {
    ActivityKind.RUN -> Icons.Rounded.DirectionsRun
    ActivityKind.WALK -> Icons.Rounded.DirectionsWalk
    ActivityKind.OTHER -> Icons.Rounded.FitnessCenter
}

fun friendlyDate(a: ActivityRecord, zone: ZoneId): String {
    val d = a.start.atZone(zone)
    val today = LocalDate.now(zone)
    val day = when (d.toLocalDate()) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
    return "$day · ${d.format(DateTimeFormatter.ofPattern("h:mm a"))}"
}
