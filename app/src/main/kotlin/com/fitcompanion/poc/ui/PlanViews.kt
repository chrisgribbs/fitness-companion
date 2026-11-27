package com.fitcompanion.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fitcompanion.core.ChallengeDefinition
import com.fitcompanion.core.PlanLevel
import com.fitcompanion.core.RaceDistance
import com.fitcompanion.core.SessionType
import com.fitcompanion.core.TrainingPlans
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.ui.theme.ChoiceChip
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.ProgressBar
import com.fitcompanion.poc.ui.theme.SportCard
import com.fitcompanion.poc.ui.theme.StatusLine
import com.fitcompanion.poc.ui.theme.Tag
import com.fitcompanion.poc.ui.theme.VoltButton
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

/** Build a 5K / 10K / half / marathon plan around a race date (typed, picked, or looked up by the AI). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanBuilderCard(app: AppController, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var distance by remember { mutableStateOf(RaceDistance.HALF) }
    var level by remember { mutableStateOf(PlanLevel.BEGINNER) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var eventName by remember { mutableStateOf("") }
    var eventUrl by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var looking by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf<AppController.EventInfo?>(null) }
    var lookupError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now(app.zone)

    SportCard(brush = Palette.ClimbGlow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.DirectionsRun, null, tint = Palette.Text)
            Spacer(Modifier.width(8.dp))
            Eyebrow("Training plan", Palette.Text)
        }
        Text("5K TO MARATHON", style = MaterialTheme.typography.headlineMedium)
        Text("A week-by-week plan built around your race day, for beginner, intermediate or advanced runners. Your Fitbit runs tick it off.",
            style = MaterialTheme.typography.bodyMedium)
        if (!open) { VoltButton("Build a training plan", icon = Icons.Rounded.Flag, onClick = { open = true }); return@SportCard }

        Eyebrow("Distance", Palette.Text)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RaceDistance.entries.forEach { d -> ChoiceChip(d.label, distance == d, { distance = d }) }
        }
        Eyebrow("Your level", Palette.Text)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanLevel.entries.forEach { l -> ChoiceChip(l.label, level == l, { level = l }) }
        }
        Text(level.who, style = MaterialTheme.typography.bodySmall)

        Eyebrow("Race day", Palette.Text)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Event, e.g. Gold Coast Marathon 2027", color = Palette.TextDim) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Volt, unfocusedBorderColor = Palette.Outline),
            shape = RoundedCornerShape(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(if (looking) "Looking it up…" else "Find the date", icon = Icons.Rounded.Search, enabled = query.isNotBlank() && !looking && app.hasKey,
                onClick = {
                    looking = true; lookup = null; lookupError = null
                    scope.launch {
                        try { lookup = app.lookupEvent("${query.trim()} (${distance.label})") }
                        catch (e: Exception) { lookupError = e.message ?: "Couldn't look that up." }
                        looking = false
                    }
                })
            GhostButton("Pick a date", icon = Icons.Rounded.CalendarMonth, onClick = { picking = true })
        }
        Text(if (app.hasKey) "\"Find the date\" asks your AI model to search the web (about half a US cent extra). Only the event name is sent."
             else "Sign in with OpenRouter on the Coach tab to look events up, or just pick the date.",
            style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        lookupError?.let { StatusLine(it, Palette.Warn) }
        lookup?.let { e ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (e.found && e.date != null) {
                    Text(e.name.ifBlank { query }, style = MaterialTheme.typography.titleMedium)
                    Text(e.date.format(dateFmt) + (if (e.location.isNotBlank()) " · ${e.location}" else ""), style = MaterialTheme.typography.bodyMedium, color = Palette.Volt)
                    if (e.distances.isNotEmpty()) Text("Distances: " + e.distances.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    if (e.url.isNotBlank()) Text(e.url, style = MaterialTheme.typography.labelSmall, color = Palette.TextDim, maxLines = 1)
                    Text("Found by AI web search: double-check on the event's own site.", style = MaterialTheme.typography.labelSmall, color = Palette.Warn)
                    VoltButton("Use this date", onClick = { date = e.date; eventName = e.name.ifBlank { query.trim() }; eventUrl = e.url })
                } else {
                    Text("Couldn't confirm a date.", style = MaterialTheme.typography.titleSmall)
                    Text(e.note.ifBlank { "Try adding the year or city, or pick the date yourself." }, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
            }
        }
        if (picking) {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = (date ?: today.plusWeeks(TrainingPlans.recommendedWeeks(distance, level).toLong())).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest = { picking = false },
                confirmButton = { TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); if (eventName.isBlank()) eventName = query.trim() }
                    picking = false
                }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }) { DatePicker(state) }
        }

        val d = date
        if (d != null) {
            if (!d.isAfter(today)) StatusLine("Pick a race day in the future.", Palette.Warn)
            else {
                val plan = remember(distance, level, d) { TrainingPlans.build(distance, level, d, today, eventName, eventUrl) }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Your plan")
                    Text((eventName.ifBlank { distance.label }) + " · " + d.format(dateFmt), style = MaterialTheme.typography.titleMedium)
                    Text("${plan.weeks.size} weeks · ${plan.runsPerWeek} runs a week · busiest week ${"%.0f".format(plan.peakWeekKm)} km · longest run ${"%.1f".format(plan.peakLongKm)} km",
                        style = MaterialTheme.typography.bodyMedium)
                    plan.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = if (it.contains("under the usual")) Palette.Warn else Palette.TextDim) }
                }
                VoltButton("Start this plan", icon = Icons.Rounded.Flag, onClick = {
                    app.addPlan(distance, level, d, eventName, eventUrl); onDone()
                })
            }
        }
    }
}

/** One line for the challenges list. */
fun planSummary(app: AppController, c: ChallengeDefinition): String {
    val plan = c.trainingPlan ?: return ""
    val today = LocalDate.now(app.zone)
    val week = plan.weekOf(today) ?: return "Starts ${plan.weeks.first().start.format(dateFmt)}"
    val prog = TrainingPlans.progress(plan, app.activities, app.zone, today).lastOrNull()
    val days = ChronoUnit.DAYS.between(today, plan.spec.eventDate)
    return "Week ${week.number} of ${plan.weeks.size} · ${prog?.runs ?: 0}/${week.sessions.size} runs this week · " +
        if (days > 0) "$days days to race day" else if (days == 0L) "race day!" else "race done"
}

/** Full screen for a training plan. */
@Composable
fun PlanDetail(app: AppController, c: ChallengeDefinition, onDeleted: () -> Unit) {
    val plan = c.trainingPlan ?: return
    val spec = plan.spec
    val today = LocalDate.now(app.zone)
    val progress = TrainingPlans.progress(plan, app.activities, app.zone, today)
    val week = plan.weekOf(today)
    val days = ChronoUnit.DAYS.between(today, spec.eventDate)
    var showAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SportCard(brush = Palette.ClimbGlow) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("${spec.level.label} ${spec.distance.label.lowercase()} plan", Palette.Climb, Modifier.weight(1f))
                if (c.paused) Tag("PAUSED", Palette.Warn)
            }
            Text(c.name.uppercase(), style = MaterialTheme.typography.headlineMedium)
            Text(spec.eventDate.format(dateFmt) + " · " + when {
                days > 1 -> "$days days to go (${days / 7} weeks)"
                days == 1L -> "tomorrow!"
                days == 0L -> "today. Good luck!"
                else -> "done"
            }, style = MaterialTheme.typography.titleMedium, color = Palette.Volt)
            if (spec.eventUrl.isNotBlank()) Text("Event website", style = MaterialTheme.typography.labelLarge, color = Palette.Volt,
                modifier = Modifier.clickable { open(spec.eventUrl) }.padding(vertical = 4.dp))
            Text("${plan.weeks.size} weeks · ${plan.runsPerWeek} runs a week · busiest week ${"%.0f".format(plan.peakWeekKm)} km · longest run ${"%.1f".format(plan.peakLongKm)} km",
                style = MaterialTheme.typography.bodySmall)
        }

        if (week != null) {
            val wp = progress.lastOrNull()
            SportCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("This week · week ${week.number} of ${plan.weeks.size}", modifier = Modifier.weight(1f))
                    Tag(week.phase.uppercase(), if (week.phase == "Race week") Palette.Volt else Palette.Climb)
                }
                week.sessions.forEachIndexed { i, s -> SessionRow(s, done = (wp?.runs ?: 0) > i) }
                val next = week.sessions.getOrNull(wp?.runs ?: 0)
                if (next != null) GhostButton(if (com.fitcompanion.poc.data.LiveHeart.HEART_RATE_ENABLED) "Live heart rate for ${next.type.label.lowercase()}"
                    else "Start ${next.type.label.lowercase()} (GPS)", icon = Icons.Rounded.DirectionsRun,
                    onClick = { app.openLive(if (next.type == SessionType.QUALITY) 3 else 2) })
                ProgressBar((wp?.fraction ?: 0.0).toFloat(), Palette.VoltGlow, height = 8.dp)
                Text("Done: ${wp?.runs ?: 0} of ${week.sessions.size} runs · ${"%.1f".format(wp?.km ?: 0.0)} of ${"%.1f".format(week.km)} km" +
                    (if ((wp?.longestKm ?: 0.0) > 0) " · longest ${"%.1f".format(wp!!.longestKm)} km" else ""),
                    style = MaterialTheme.typography.bodyMedium)
                Text(when (week.phase) {
                    "Base" -> "Base weeks: relaxed running to build the habit before the main plan starts."
                    "Cutback" -> "Lighter week so your body can absorb the training."
                    "Peak" -> "Your biggest weeks. Sleep and easy days matter most now."
                    "Taper" -> "Running less so you arrive fresh: keep a little race pace."
                    "Race week" -> "Keep it short and easy. You've done the work."
                    else -> "Building gradually: most runs easy, long run growing a little each week."
                }, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        } else StatusLine("Your plan starts ${plan.weeks.first().start.format(dateFmt)}.", Palette.Volt)

        if (progress.size > 1) SportCard {
            Eyebrow("Recent weeks")
            progress.dropLast(1).takeLast(4).reversed().forEach { w ->
                Row {
                    Text("Week ${w.week.number}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(80.dp))
                    Text("${w.runs}/${w.week.sessions.size} runs · ${"%.1f".format(w.km)} of ${"%.1f".format(w.week.km)} km",
                        style = MaterialTheme.typography.bodyMedium, color = if (w.runs >= w.week.sessions.size) Palette.Good else Palette.Text)
                }
            }
        }

        SportCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Whole plan", modifier = Modifier.weight(1f))
                Text(if (showAll) "HIDE" else "SHOW ALL", style = MaterialTheme.typography.labelLarge, color = Palette.Volt,
                    modifier = Modifier.clickable { showAll = !showAll }.padding(4.dp))
            }
            (if (showAll) plan.weeks else plan.weeks.filter { w -> week == null || w.number in week.number..week.number + 3 }).forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${w.number}", style = MaterialTheme.typography.titleSmall, color = if (w == week) Palette.Volt else Palette.TextDim, modifier = Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${w.start.format(DateTimeFormatter.ofPattern("d MMM"))} · ${w.phase}", style = MaterialTheme.typography.bodyMedium)
                        Text("${w.sessions.size} runs · ${"%.1f".format(w.km)} km · long ${"%.1f".format(w.longKm)} km", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    }
                }
            }
        }

        SportCard {
            Eyebrow("How this plan is built")
            plan.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            val ids = listOf("easy-most-of-the-time", "single-run-spikes", "weekly-progression", "taper", "talk-test", "plan-lengths") +
                if (spec.distance == RaceDistance.FIVE_K && spec.level == PlanLevel.BEGINNER) listOf("couch-to-5k") else emptyList()
            app.research.notes.filter { it.id in ids }.forEach { n ->
                Column(Modifier.clickable(enabled = n.url.isNotBlank()) { open(n.url) }.padding(vertical = 4.dp)) {
                    Text(n.title, style = MaterialTheme.typography.titleSmall)
                    Text(n.finding, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    Text(n.source + " · " + n.type, style = MaterialTheme.typography.labelSmall, color = Palette.Volt)
                }
            }
            Text("Plans are general guidance, not medical advice. If something hurts, rest and get it checked.",
                style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c.paused) VoltButton("Resume plan", onClick = { app.resume(c) }, modifier = Modifier.fillMaxWidth())
            else GhostButton("Pause plan", onClick = { app.pause(c) }, modifier = Modifier.fillMaxWidth())
            GhostButton(if (confirmDelete) "Tap to confirm" else "Delete plan", color = Palette.Heart, modifier = Modifier.fillMaxWidth(),
                onClick = { if (confirmDelete) { app.delete(c.id); onDeleted() } else confirmDelete = true })
        }
    }
}

@Composable
private fun SessionRow(s: com.fitcompanion.core.PlannedSession, done: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Text(if (done) "✓" else "○", color = if (done) Palette.Good else Palette.TextDim, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text("${s.type.label} · ${"%.1f".format(s.km)} km", style = MaterialTheme.typography.titleSmall,
                color = when (s.type) { SessionType.LONG -> Palette.Volt; SessionType.QUALITY -> Palette.Climb; SessionType.RACE -> Palette.Volt; else -> Palette.Text })
            Text(s.detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
    }
    Spacer(Modifier.size(2.dp))
}
