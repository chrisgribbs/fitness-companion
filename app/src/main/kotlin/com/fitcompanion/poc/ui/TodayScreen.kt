package com.fitcompanion.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fitcompanion.core.ActivityKind
import com.fitcompanion.core.ActivityRecord
import com.fitcompanion.core.ChallengeMetric
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.ui.theme.BigStat
import com.fitcompanion.poc.ui.theme.DemoBanner
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.ProgressBar
import com.fitcompanion.poc.ui.theme.ProgressRing
import com.fitcompanion.poc.ui.theme.ScreenTitle
import com.fitcompanion.poc.ui.theme.SectionHeader
import com.fitcompanion.poc.ui.theme.SportCard
import com.fitcompanion.poc.ui.theme.StatTile
import com.fitcompanion.poc.ui.theme.StatusLine
import com.fitcompanion.poc.ui.theme.VoltButton
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Shown as a target only; there's no Fitbit step goal in the data yet. */
private const val STEP_TARGET = 10_000

@Composable
fun TodayScreen(app: AppController, onOpenActivity: (ActivityRecord) -> Unit, onGoTo: (Int) -> Unit, contentPadding: PaddingValues) {
    var metric by remember { mutableStateOf<MetricKind?>(null) }
    metric?.let { MetricDetail(app, it) { metric = null } }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now(app.zone)
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun dateOf(a: ActivityRecord) = a.start.atZone(app.zone).toLocalDate()
    val thisWeek = app.activities.filter { !dateOf(it).isBefore(weekStart) }
    val todayMetric = app.daily.firstOrNull { it.date == today }
    val latestRhr = app.daily.sortedByDescending { it.date }.firstOrNull { it.restingHeartRate != null }
    val latestHrv = app.daily.sortedByDescending { it.date }.firstOrNull { it.hrvMillis != null }
    val lastSleep = app.daily.sortedByDescending { it.date }.firstOrNull { it.sleepMinutes != null }

    val greeting = when (LocalTime.now(app.zone).hour) { in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        item {
            ScreenTitle("Today", subtitle = "$greeting · ${today.format(DateTimeFormatter.ofPattern("EEE d MMM"))}") {
                IconButton(onClick = { scope.launch { app.refresh() } }, enabled = !app.busy) {
                    Icon(Icons.Rounded.Sync, "Sync", tint = if (app.busy) Palette.TextDim else Palette.Volt)
                }
            }
            SyncBanner(app) { onGoTo(3) }
            app.message?.takeIf { !app.busy && it.isNotBlank() }?.let { Spacer(Modifier.height(6.dp)); StatusLine(it, Palette.Warn) }
        }
        if (app.update != null) item { UpdateBanner(app) }
        if (app.useDemoData) item { DemoBanner() }

        if (app.activities.isEmpty() && app.daily.isEmpty()) {
            item {
                SportCard(brush = Palette.VoltGlow) {
                    Text("LET'S GET MOVING", style = MaterialTheme.typography.headlineMedium, color = Palette.Bg)
                    Text("Connect Health Connect to pull in your Fitbit runs, walks and sleep — or try the app with demo data.",
                        color = Palette.Bg, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.clip(CircleShape).background(Palette.Bg).clickable { onGoTo(3) }.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text("CONNECT", style = MaterialTheme.typography.labelLarge, color = Palette.Volt)
                        }
                        Box(Modifier.clip(CircleShape).background(Palette.Bg.copy(alpha = 0.15f)).clickable { scope.launch { app.setDemo(true) } }.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text("TRY DEMO", style = MaterialTheme.typography.labelLarge, color = Palette.Bg)
                        }
                    }
                }
            }
        }

        // Hero: steps ring + this week's run/walk distance.
        item {
            SportCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val steps = todayMetric?.steps
                    Box(Modifier.clip(CircleShape).clickable { metric = MetricKind.STEPS }) {
                    ProgressRing((steps ?: 0L).toFloat() / STEP_TARGET, 132.dp, 14.dp, Palette.VoltGlow) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(steps?.let { "%,d".format(it) } ?: "—", style = MaterialTheme.typography.displaySmall,
                                color = if (steps == null) Palette.TextDim else Palette.Text)
                            Eyebrow("steps today")
                        }
                    }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        WeekKind("Runs", ActivityKind.RUN, thisWeek, app.zone)
                        WeekKind("Walks", ActivityKind.WALK, thisWeek, app.zone)
                    }
                }
                Text("Steps are today (target ${"%,d".format(STEP_TARGET)}). Run and walk totals are this week, Monday to today.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }

        // One clear action: start a workout (GPS route + optional heart rate).
        item {
            val live = com.fitcompanion.poc.data.LiveHeart
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(if (live.recording) Palette.Heart else Palette.Volt)
                .clickable { app.openLive() }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (live.recording) Icons.Rounded.Favorite else Icons.Rounded.PlayArrow, null, tint = Palette.Bg)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (live.recording) "WORKOUT RECORDING" else "START A WORKOUT", style = MaterialTheme.typography.titleLarge, color = Palette.Bg)
                    Text(if (live.recording) listOfNotNull(live.bpm?.let { "$it bpm" }, if (live.gpsPoints.size > 1) "%.2f km".format(live.gpsDistance / 1000) else null)
                            .joinToString(" · ").ifBlank { "Recording" } + " · tap to open"
                         else if (com.fitcompanion.poc.data.LiveHeart.HEART_RATE_ENABLED) "Map your route · optional live heart rate" else "Map your route, distance and pace", style = MaterialTheme.typography.bodySmall, color = Palette.Bg)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Palette.Bg)
            }
        }

        item {
            WalkDetectAutoAsk(app)
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val wd = com.fitcompanion.poc.data.WalkDetector
            if (app.walkDetect && !app.walkDetectOffered && wd.asked(ctx) && !wd.hasPermission(ctx)) WalkDetectOffer(app)
        }

        // Plan a walk between two places.
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Palette.Surface)
                .clickable { app.showTrip = true }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Map, null, tint = Palette.Volt)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("PLAN A WALK", style = MaterialTheme.typography.titleMedium)
                    Text("From here to there: time, steps, hills, and a workout plan", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextDim)
            }
        }

        // Push-up counter.
        item {
            val today = java.time.LocalDate.now()
            val done = app.pushups.filter { it.start.atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today }.sumOf { it.reps }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Palette.Surface)
                .clickable { app.showPushups = true }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FitnessCenter, null, tint = Palette.Volt)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("PUSH-UPS", style = MaterialTheme.typography.titleMedium)
                    Text(if (done > 0) "$done today · tap to do another set" else "The phone counts them for you", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextDim)
            }
        }

        // Latest activity.
        app.recentActivities.firstOrNull()?.let { latest ->
            item { SectionHeader("Latest") }
            item { LatestCard(app, latest) { onOpenActivity(latest) } }
        }

        // One-time choice: accurate climb from the terrain map (for Fitbit and in-app workouts alike).
        if (app.terrainEnabled == null && app.hasRoutes) item { TerrainChoice(app) }

        // Recovery.
        item { SectionHeader("Recovery") }
        item { Text("Tap a tile for charts and day-by-day details.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
        item {
            val hr = app.latestHr
            val ago = hr?.let { java.time.Duration.between(it.first, java.time.Instant.now()).toMinutes() }
            StatTile(Icons.Rounded.Favorite, "Heart rate · latest", hr?.second?.toString(), "bpm", Palette.Heart, Modifier.fillMaxWidth(),
                footnote = when {
                    hr == null -> "No recent reading in Health Connect"
                    ago!! < 2 -> "Just now (as last synced from your watch)"
                    ago < 60 -> "$ago min ago (as last synced from your watch)"
                    else -> "${ago / 60} h ${ago % 60} min ago · your watch hasn't synced since"
                })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(Icons.Rounded.Bedtime, "Sleep", lastSleep?.sleepMinutes?.let { "${it / 60}:%02d".format(it % 60) }, "h", Palette.Sleep,
                    Modifier.weight(1f).clip(com.fitcompanion.poc.ui.theme.CardShape).clickable { metric = MetricKind.SLEEP }, footnote = lastSleep?.date?.let { "Night ending ${it.format(DateTimeFormatter.ofPattern("d MMM"))}" } ?: "No data")
                StatTile(Icons.Rounded.Favorite, "Resting HR", latestRhr?.restingHeartRate?.toString(), "bpm", Palette.Heart,
                    Modifier.weight(1f).clip(com.fitcompanion.poc.ui.theme.CardShape).clickable { metric = MetricKind.RESTING_HR }, footnote = latestRhr?.date?.format(DateTimeFormatter.ofPattern("d MMM")) ?: "No data")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(Icons.Rounded.MonitorHeart, "HRV", latestHrv?.hrvMillis?.let { "%.0f".format(it) }, "ms", Palette.Climb,
                    Modifier.weight(1f).clip(com.fitcompanion.poc.ui.theme.CardShape).clickable { metric = MetricKind.HRV }, footnote = latestHrv?.date?.format(DateTimeFormatter.ofPattern("d MMM")) ?: "No data")
                val climbWeek = thisWeek.mapNotNull { it.ascentMeters }
                StatTile(Icons.Rounded.Terrain, "Climb · week", if (climbWeek.isEmpty()) null else "%.0f".format(climbWeek.sum()), "m", Palette.Volt,
                    Modifier.weight(1f).clip(com.fitcompanion.poc.ui.theme.CardShape).clickable { metric = MetricKind.CLIMB },
                    footnote = if (climbWeek.isEmpty()) "No climb data · tap for why" else "${climbWeek.size} of ${thisWeek.size} activities")
            }
        }

        // Challenges.
        if (app.activeChallenges.isNotEmpty()) {
            item { SectionHeader("Challenges", "See all") { onGoTo(1) } }
            items(app.activeChallenges.filter { !it.paused }.take(2), key = { "c-" + it.id }) { c ->
                val p = app.progressOf(c)
                val brush = if (c.metric == ChallengeMetric.ASCENT) Palette.ClimbGlow else Palette.VoltGlow
                SportCard(onClick = { app.openChallengeId = c.id; onGoTo(1) }, padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing(p.fraction.toFloat(), 54.dp, 7.dp, brush) {
                            Text("${p.percent.toInt()}%", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name.uppercase(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${meters(p.totalMeters)} of ${meters(p.goalMeters)} · this week +${meters(p.thisWeekMeters)}",
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextDim)
                    }
                }
            }
        }

        // Recent list.
        if (app.recentActivities.size > 1) {
            item { SectionHeader("Recent") }
            items(app.recentActivities.drop(1).take(8), key = { "a-" + it.id }) { a -> ActivityRow(app, a) { onOpenActivity(a) } }
        }
    }
}

@Composable
private fun WeekKind(label: String, kind: ActivityKind, week: List<ActivityRecord>, zone: java.time.ZoneId) {
    val list = week.filter { it.kind == kind }.sortedBy { it.start }
    val km = list.mapNotNull { it.distanceMeters }.sum()
    val today = java.time.LocalDate.now(zone)
    val todayKm = list.filter { it.start.atZone(zone).toLocalDate() == today }.mapNotNull { it.distanceMeters }.sum()
    // Which days they happened on, so a Monday run doesn't look like today's.
    val days = list.map { it.start.atZone(zone).toLocalDate() }.distinct().joinToString(", ") {
        if (it == today) "Today" else it.format(java.time.format.DateTimeFormatter.ofPattern("EEE"))
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kind.icon(), null, tint = kind.color(), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Eyebrow("$label this week · ${list.size}")
        }
        BigStat(if (list.isEmpty()) "0.0" else kmNumber(km), "km", color = kind.color())
        Text(when {
            list.isEmpty() -> "None yet this week"
            todayKm > 0 && days == "Today" -> "All today"
            todayKm > 0 -> "${kmNumber(todayKm)} km today · $days"
            else -> "On $days · none today"
        }, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2)
    }
}

@Composable
private fun LatestCard(app: AppController, a: ActivityRecord, onClick: () -> Unit) {
    val jv = app.journalVersion
    val photos = remember(jv, a.id) { app.photos.photos(a.id) }
    SportCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(a.kind.color().copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(a.kind.icon(), null, tint = a.kind.color())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.kind.label().uppercase(), style = MaterialTheme.typography.titleLarge)
                Text(friendlyDate(a, app.zone), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            Icon(Icons.Rounded.PhotoCamera, null, tint = if (photos.isEmpty()) Palette.TextDim else Palette.Volt, modifier = Modifier.size(18.dp))
            if (photos.isNotEmpty()) Text(" ${photos.size}", style = MaterialTheme.typography.labelMedium, color = Palette.Volt)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column { Eyebrow("Distance"); BigStat(a.distanceMeters?.let { kmNumber(it) }, "km", color = a.kind.color()) }
            Column { Eyebrow("Pace"); BigStat(paceText(a), "/km") }
            Column { Eyebrow("Climb"); BigStat(a.ascentMeters?.let { "%.0f".format(it) }, "m", a.ascentQuality) }
        }
        PhotoStrip(photos.take(6), onClick = onClick)
        val note = remember(jv, a.id) { app.photos.note(a.id) }
        if (note.isNotBlank()) Text("“$note”", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        else Text("Tap to add photos and how it felt", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
}

@Composable
fun ActivityRow(app: AppController, a: ActivityRecord, onClick: () -> Unit) {
    val jv = app.journalVersion
    val photoCount = remember(jv, a.id) { app.photos.photos(a.id).size }
    Row(
        Modifier.fillMaxWidth().clip(com.fitcompanion.poc.ui.theme.CardShape).background(Palette.Surface).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(a.kind.color().copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(a.kind.icon(), null, tint = a.kind.color(), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.kind.label(), style = MaterialTheme.typography.titleSmall)
            Text(friendlyDate(a, app.zone), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        if (photoCount > 0) {
            Icon(Icons.Rounded.PhotoCamera, null, tint = Palette.Volt, modifier = Modifier.size(14.dp))
            Text(" $photoCount  ", style = MaterialTheme.typography.labelSmall, color = Palette.Volt)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(a.distanceMeters?.let { "${kmNumber(it)} km" } ?: "—", style = MaterialTheme.typography.titleLarge, color = a.kind.color())
            a.ascentMeters?.let { Text("↑ %.0f m".format(it), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
        }
    }
}

@Composable
fun SpacerLine() = Spacer(Modifier.height(1.dp).fillMaxWidth().background(Palette.Outline))
