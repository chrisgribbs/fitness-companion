package com.fitcompanion.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fitcompanion.core.ActivityRecord
import com.fitcompanion.core.ChallengeDefinition
import com.fitcompanion.core.ChallengeMetric
import com.fitcompanion.core.ChallengeProgress
import com.fitcompanion.core.Everest
import com.fitcompanion.core.JourneyRoute
import com.fitcompanion.core.WaypointCheckStatus
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.ui.theme.BigStat
import com.fitcompanion.poc.ui.theme.CardShape
import com.fitcompanion.poc.ui.theme.ChoiceChip
import com.fitcompanion.poc.ui.theme.DemoBanner
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.ProgressBar
import com.fitcompanion.poc.ui.theme.ScreenTitle
import com.fitcompanion.poc.ui.theme.SportCard
import com.fitcompanion.poc.ui.theme.StatusLine
import com.fitcompanion.poc.ui.theme.Tag
import com.fitcompanion.poc.ui.theme.VoltButton
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

@Composable
fun ChallengesScreen(app: AppController, contentPadding: PaddingValues, onOpenActivity: (ActivityRecord) -> Unit) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val open = app.challenges.firstOrNull { it.id == app.openChallengeId }
    if (open != null) {
        androidx.activity.compose.BackHandler { app.openChallengeId = null }
        ChallengeDetail(app, open, contentPadding, onOpenActivity) { app.openChallengeId = null }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenTitle("Challenges", subtitle = "Go further") {
                IconButton(onClick = { creating = !creating }) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(if (creating) Palette.SurfaceHigh else Palette.Volt), contentAlignment = Alignment.Center) {
                        Icon(if (creating) Icons.Rounded.Close else Icons.Rounded.Add, "New challenge", tint = if (creating) Palette.Text else Palette.Bg)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    StatusLine(app.message ?: app.lastSynced?.let { "Synced ${it.format(DateTimeFormatter.ofPattern("h:mm a"))}" } ?: "Not synced yet",
                        if (app.lastSynced != null) Palette.Good else Palette.Warn)
                }
                Eyebrow("Demo")
                Spacer(Modifier.width(6.dp))
                Switch(app.useDemoData, { on -> scope.launch { app.setDemo(on) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = Palette.Bg, checkedTrackColor = Palette.Warn))
            }
        }
        if (app.useDemoData) item { DemoBanner() }
        if (creating) item { CreateChallenge(app) { creating = false } }

        if (app.challenges.isEmpty() && !creating) item {
            SportCard(brush = Palette.ClimbGlow) {
                Icon(Icons.Rounded.Terrain, null, tint = Palette.Text, modifier = Modifier.size(36.dp))
                Text("PICK YOUR MOUNTAIN", style = MaterialTheme.typography.headlineMedium)
                Text("Climb Everest one hill at a time, or have AI turn any journey into a challenge — Brisbane to the Gold Coast, coast to coast across America…",
                    style = MaterialTheme.typography.bodyMedium)
                VoltButton("Start a challenge", onClick = { creating = true }, icon = Icons.Rounded.Add)
            }
        }

        if (app.challenges.isNotEmpty()) item {
            val active = app.challenges.count { !it.paused }
            Text("$active active${if (app.challenges.size > active) " · ${app.challenges.size - active} paused" else ""}. " +
                (if (active > 1) "Each one counts your activities separately, so one walk moves you forward in every active challenge it fits." else "Tap a challenge to see its map and details."),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        items(app.challenges, key = { it.id }) { c -> ChallengeSummary(app, c, app.progressOf(c)) { app.openChallengeId = c.id } }
    }
}

/** Full screen for one challenge. */
@Composable
private fun ChallengeDetail(app: AppController, c: ChallengeDefinition, contentPadding: PaddingValues, onOpenActivity: (ActivityRecord) -> Unit, onBack: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "All challenges", tint = Palette.Text) }
                Text("ALL CHALLENGES", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim,
                    modifier = Modifier.clickable(onClick = onBack))
            }
        }
        item { if (c.plan != null) PlanDetail(app, c, onDeleted = onBack) else ChallengeCard(app, c, app.progressOf(c), onOpenActivity, onDeleted = onBack) }
    }
}

/** Compact card for the list: name, %, bar and what's next. Tap to open. */
@Composable
private fun ChallengeSummary(app: AppController, c: ChallengeDefinition, p: ChallengeProgress, onOpen: () -> Unit) {
    val climb = c.metric == ChallengeMetric.ASCENT
    val accent = if (climb) Palette.Climb else Palette.Walk
    Column(Modifier.fillMaxWidth().clip(CardShape).background(Palette.Surface).clickable(onClick = onOpen)
        .background(Brush.horizontalGradient(listOf(accent.copy(alpha = if (c.paused) 0.06f else 0.18f), Palette.Surface))).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (climb) Icons.Rounded.Terrain else Icons.Rounded.Place, null, tint = accent)
            Spacer(Modifier.width(8.dp))
            Eyebrow(if (c.plan != null) "Training plan" else if (climb) "Climb" else "Journey", accent, Modifier.weight(1f))
            if (c.paused) Tag("PAUSED", Palette.Warn) else if (p.completedOn != null) Tag("DONE 🏆", Palette.Volt)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(c.name.uppercase(), style = MaterialTheme.typography.titleLarge, maxLines = 2, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text("%.0f%%".format(p.percent), style = MaterialTheme.typography.headlineMedium, color = if (c.paused) Palette.TextDim else accent)
        }
        ProgressBar(p.fraction.toFloat(), if (climb) Palette.ClimbGlow else Palette.VoltGlow, height = 8.dp)
        if (c.plan != null) Text(planSummary(app, c), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2)
        else Text(listOfNotNull(
            "${meters(p.totalMeters)} of ${meters(p.goalMeters)}",
            p.next?.let { "next: ${it.name} in ${meters(it.atMeters - p.totalMeters)}" },
            listOfNotNull(if (c.includeRuns) "runs" else null, if (c.includeWalks) "walks" else null).joinToString(" + "),
        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2)
    }
}

@Composable
private fun ChallengeCard(app: AppController, c: ChallengeDefinition, p: ChallengeProgress, onOpenActivity: (ActivityRecord) -> Unit, onDeleted: () -> Unit = {}) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    val climb = c.metric == ChallengeMetric.ASCENT
    val brush = if (climb) Palette.ClimbGlow else Palette.VoltGlow
    val accent = if (climb) Palette.Climb else Palette.Walk
    val jv = app.journalVersion
    val counted = remember(app.activities, c) {
        app.activities.filter { c.counts(it.kind) && !it.start.atZone(app.zone).toLocalDate().isBefore(c.startDate)
            && (c.startedAt == null || !it.start.isBefore(c.startedAt)) && !c.isPausedAt(it.start) }
            .sortedByDescending { it.start }
    }
    val photoSets = remember(counted, jv) { counted.map { it to app.photos.photos(it.id) }.filter { it.second.isNotEmpty() } }

    Column(Modifier.fillMaxWidth().clip(CardShape).background(Palette.Surface)) {
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), Palette.Surface))).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(if (climb) "Climbing challenge" else "Virtual journey", accent, Modifier.weight(1f))
                if (c.createdByAi) Tag("AI-DESIGNED", Palette.Volt)
            }
            Text(c.name.uppercase(), style = MaterialTheme.typography.headlineMedium)
            Text(listOfNotNull(if (c.includeRuns) "Runs" else null, if (c.includeWalks) "Walks" else null).joinToString(" + ") +
                " · since " + (c.startedAt?.atZone(app.zone)?.format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a"))
                    ?: c.startDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }

        if (climb) {
            MountainGraphic(p, c.milestones, Modifier.padding(horizontal = 12.dp))
        } else if (c.route.size >= 2) {
            Box(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp))) {
                JourneyMap(JourneyRoute(c.route), p.totalMeters, milestoneMeters = if (c.presetId.isNotBlank()) c.milestones.map { it.atMeters } else null)
            }
        }

        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (p.includesDemoData) DemoBanner()

            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.1f".format(p.percent), style = MaterialTheme.typography.displayLarge, color = accent)
                Text("%", style = MaterialTheme.typography.headlineMedium, color = accent, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text("${meters(p.totalMeters)} ${if (climb) "climbed" else "covered"}", style = MaterialTheme.typography.titleMedium)
                    Text("${meters(p.remainingMeters)} to go", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
            }
            ProgressBar(p.fraction.toFloat(), brush)

            if (climb) {
                val pct = p.thisWeekMeters / c.goalMeters * 100
                Text("This week you climbed ${meters(p.thisWeekMeters)} — ${"%.1f".format(pct)}% of ${if (c.goalMeters == Everest.HEIGHT_METERS) "Everest" else "the goal"}.",
                    style = MaterialTheme.typography.titleMedium)
                CountedThisWeek(app, c)
            } else if (c.route.size >= 2 && c.presetId.isNotBlank()) {
                val route = remember(c.route) { JourneyRoute(c.route) }
                val finished = p.totalMeters >= route.totalMeters
                val last = p.reached.lastOrNull()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (finished) Icons.Rounded.Flag else Icons.Rounded.Place, null, tint = Palette.Volt)
                    Spacer(Modifier.width(8.dp))
                    Text(when {
                        finished -> "You've finished the ${c.name}!"
                        last == null -> "Just set off · ${meters(p.totalMeters)} along the trail"
                        else -> "Past ${last.name}" + (p.next?.let { " · ${meters(it.atMeters - p.totalMeters)} to ${it.name}" } ?: "")
                    }, style = MaterialTheme.typography.titleMedium)
                }
                val here = route.pointAt(p.totalMeters.coerceAtMost(route.totalMeters))
                TrailPhotoPanel(app, here.first, here.second, when {
                    finished -> c.milestones.lastOrNull()?.name ?: "the finish"
                    last == null -> "the start"
                    else -> "near ${last.name}"
                })
            } else if (c.route.size >= 2) {
                val pos = JourneyRoute(c.route).positionAt(p.totalMeters)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (pos.finished) Icons.Rounded.Flag else Icons.Rounded.Place, null, tint = Palette.Volt)
                    Spacer(Modifier.width(8.dp))
                    Text(if (pos.finished) "You've arrived at ${c.route.last().name}!"
                        else "Past ${c.route[pos.lastPassedIndex].name} · ${meters(pos.metersToNextWaypoint)} to ${c.route[pos.lastPassedIndex + 1].name}",
                        style = MaterialTheme.typography.titleMedium)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val wk = kmOrM(p.thisWeekMeters, climb); val mo = kmOrM(p.thisMonthMeters, climb)
                Column { Eyebrow("This week"); BigStat(wk.first, wk.second) }
                Column { Eyebrow("This month"); BigStat(mo.first, mo.second) }
            }
            WeeklyBars(p, accent)

            p.next?.let { n ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHigh).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Flag, null, tint = accent)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Eyebrow("Next milestone")
                        Text("${n.name} · ${meters(n.atMeters - p.totalMeters)} away", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            if (p.reached.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.reached.forEach { Tag("✓ ${it.name}", accent) }
                }
            }
            p.completedOn?.let { Text("COMPLETED ${it.format(DateTimeFormatter.ofPattern("d MMM yyyy"))} 🏆", style = MaterialTheme.typography.headlineSmall, color = Palette.Volt) }
            p.estimate?.let {
                Text("Estimate · at your recent ${meters(it.metersPerDay * 7)} a week you'd finish around ${it.date.format(DateTimeFormatter.ofPattern("MMM yyyy"))} (based on the last ${it.basisDays} days).",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            if (p.activitiesMissingData > 0) Text(
                "${p.activitiesMissingData} matching activit${if (p.activitiesMissingData == 1) "y has" else "ies have"} no ${if (climb) "climb" else "distance"} data, so ${if (p.activitiesMissingData == 1) "it isn't" else "they aren't"} counted.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
            if (!climb && c.presetId.isNotBlank()) Text("Trail distance measured along the mapped path. ${c.credit}. Map © OpenStreetMap contributors · OpenFreeMap.",
                style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
            else if (!climb && c.route.size >= 2) Text("Route is approximate: straight lines between ${c.route.size} waypoints. Map © OpenStreetMap contributors · OpenFreeMap.",
                style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)

            if (photoSets.isNotEmpty()) {
                Eyebrow("Along the way")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    photoSets.take(10).forEach { (a, files) ->
                        PhotoThumb(files.first(), Modifier.size(84.dp).clickable { onOpenActivity(a) })
                    }
                }
            }

            if (confirmRestart) Text("Restart sets progress back to zero. Only activities from now on will count.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
            if (c.paused) Text("Paused: activities you do now don't count toward this challenge. Your progress so far is kept.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (c.paused) VoltButton("Resume", onClick = { app.resume(c) }, modifier = Modifier.fillMaxWidth())
                else GhostButton("Pause", onClick = { app.pause(c) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(if (confirmRestart) "Tap to confirm" else "Restart", modifier = Modifier.weight(1f),
                        onClick = { if (confirmRestart) { app.resetStart(c); confirmRestart = false } else confirmRestart = true })
                    GhostButton(if (confirmDelete) "Tap to confirm" else "Delete", color = Palette.Heart, modifier = Modifier.weight(1f),
                        onClick = { if (confirmDelete) { app.delete(c.id); onDeleted() } else confirmDelete = true })
                }
            }
        }
    }
}

private fun kmOrM(m: Double, climb: Boolean): Pair<String, String> =
    if (climb || m < 1000) "%,.0f".format(m) to "m" else kmNumber(m) to "km"

@Composable
private fun WeeklyBars(p: ChallengeProgress, accent: Color) {
    val max = (p.weekly.maxOfOrNull { it.meters } ?: 0.0).coerceAtLeast(1.0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("Last 8 weeks")
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            p.weekly.forEachIndexed { i, wk ->
                val current = i == p.weekly.lastIndex
                Box(Modifier.weight(1f).height((60 * (wk.meters / max)).coerceAtLeast(3.0).dp)
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .background(if (current) Brush.verticalGradient(listOf(Palette.Volt, accent)) else Brush.verticalGradient(listOf(Palette.Outline, Palette.Outline))))
            }
        }
    }
}

@Composable
private fun CreateChallenge(app: AppController, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var runs by remember { mutableStateOf(true) }
    var walks by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PlanBuilderCard(app, onDone)
        app.presets.forEach { PresetCard(app, it, onDone) }

        SportCard(brush = Palette.ClimbGlow) {
            Eyebrow("Classic", Palette.Text)
            Text("CLIMB EVEREST", style = MaterialTheme.typography.headlineMedium)
            Text("Add up every metre you climb until you reach 8,849 m.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Runs", runs, { runs = !runs })
                ChoiceChip("Walks", walks, { walks = !walks })
            }
            VoltButton("Start the climb", onClick = { app.addEverest(runs, walks); onDone() }, enabled = runs || walks, icon = Icons.Rounded.Terrain)
        }

        SportCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Volt)
                Spacer(Modifier.width(8.dp))
                Eyebrow("Design your own with AI", Palette.Volt)
            }
            Text("DREAM UP A JOURNEY", style = MaterialTheme.typography.headlineMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Brisbane → Gold Coast" to "A coastal walk from Brisbane to Coolangatta on the Gold Coast, following the coast where possible. Walks only.",
                    "Sunshine Coast" to "Walk the Sunshine Coast coastline from Caloundra to Noosa Heads, passing the main beaches. Walks only.",
                    "Cali → New York" to "Walk across America from San Francisco, California to New York City, roughly along Interstate 80. Runs and walks.",
                    "Kilimanjaro ×2" to "Climb the height of Kilimanjaro twice, with fun milestones on the way. Runs and walks.",
                ).forEach { (label, prompt) -> ChoiceChip(label, text == prompt, { text = prompt }) }
            }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3,
                placeholder = { Text("Describe it, or tap the mic — e.g. “walk the Great Ocean Road, walks only”", color = Palette.TextDim) },
                trailingIcon = { MicButton("Describe your challenge", onText = { text = it }) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Volt, unfocusedBorderColor = Palette.Outline),
                shape = RoundedCornerShape(16.dp))
            Text("Only this description is sent to OpenRouter — none of your health or location data.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            VoltButton(if (app.busy) "Designing…" else "Design it", onClick = { scope.launch { app.designChallenge(text) } },
                enabled = text.isNotBlank() && !app.busy && app.hasKey, icon = Icons.Rounded.AutoAwesome)
            if (!app.hasKey) Text("Sign in with OpenRouter in the Coach tab to use this.", style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
            app.proposal?.let { Proposal(app, it, onDone) }
        }
    }
}

@Composable
private fun Proposal(app: AppController, prop: AppController.ChallengeProposal, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    if (prop.definition == null) {
        Text("The AI's challenge had problems:", color = Palette.Heart, style = MaterialTheme.typography.titleSmall)
        prop.errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        return
    }
    val d = prop.definition
    val climb = d.metric == ChallengeMetric.ASCENT
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.SurfaceHigh).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow("Proposal", Palette.Volt)
        Text(d.name.uppercase(), style = MaterialTheme.typography.headlineSmall)
        if (d.description.isNotBlank()) Text(d.description, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val g = kmOrM(d.goalMeters, climb)
            Column { Eyebrow(if (climb) "Climb" else "Distance"); BigStat(g.first, g.second) }
            Column { Eyebrow("Counts"); Text(listOfNotNull(if (d.includeRuns) "Runs" else null, if (d.includeWalks) "Walks" else null).joinToString(" + "), style = MaterialTheme.typography.titleLarge) }
        }
        if (!climb) Text(if (prop.checked && prop.pathNotes.firstOrNull()?.startsWith("Follows") == true) "Distance measured by the app along real paths."
            else "Distance measured by the app between the stops.", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        prop.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }

        if (d.route.size >= 2) {
            Box(Modifier.clip(RoundedCornerShape(16.dp))) { JourneyMap(JourneyRoute(d.route), 0.0) }
            app.placeProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Volt) }
            prop.problems.forEach { pr ->
                Text((if (pr.blocksSaving) "✕ " else "⚠ ") + pr.message, style = MaterialTheme.typography.bodySmall,
                    color = if (pr.blocksSaving) Palette.Heart else Palette.Warn)
            }
            if (prop.problems.any { it.blocksSaving })
                Text("This route can't be saved as it is. Tap Ask again to have the AI fix it, or reword your request (e.g. add the state or country).",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            if (!prop.checked) {
                Text("The route is checked against OpenStreetMap and real walking paths before saving (place names and stop coordinates only).",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                GhostButton("Check route", onClick = { scope.launch { app.checkPlaces() } }, enabled = !app.busy, icon = Icons.Rounded.Place)
            } else {
                prop.pathNotes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Follows")) Palette.Good else Palette.TextDim) }
                prop.checks.forEach { c ->
                    val (mark, color) = when (c.status) {
                        WaypointCheckStatus.VERIFIED -> "✓" to Palette.Good
                        WaypointCheckStatus.MOVED -> "⚠ adjusted ${"%.0f".format(c.offsetKm)} km" to Palette.Warn
                        WaypointCheckStatus.TOO_FAR -> "⚠ only a same-named place ${"%,.0f".format(c.offsetKm)} km away; kept the AI's spot" to Palette.Warn
                        WaypointCheckStatus.NOT_FOUND -> "? not on the map; kept the AI's spot" to Palette.TextDim
                    }
                    Row { Text(c.name, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp)); Text(mark, color = color, style = MaterialTheme.typography.labelMedium) }
                }
                GhostButton("Check again", onClick = { scope.launch { app.checkPlaces() } }, enabled = !app.busy, icon = Icons.Rounded.Place)
                Text("Place and path data © OpenStreetMap contributors (routing by FOSSGIS).", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
            }
        } else if (d.milestones.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.milestones.forEach { Tag("${it.name} · ${meters(it.atMeters)}", Palette.Climb) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val blocked = prop.problems.any { it.blocksSaving } || (!climb && d.route.size >= 2 && !prop.checked)
            if (prop.problems.any { it.blocksSaving }) VoltButton("Ask again", onClick = { scope.launch { app.designChallenge(prop.prompt) } }, enabled = !app.busy)
            else VoltButton("Save challenge", onClick = { app.acceptProposal(); onDone() }, enabled = !app.busy && !blocked)
            GhostButton("Discard", onClick = { app.proposal = null })
        }
    }
}


/** Every workout this challenge counts this week, with its climb and where that number came from. */
@Composable
private fun CountedThisWeek(app: AppController, c: ChallengeDefinition) {
    var open by remember { mutableStateOf(false) }
    val monday = java.time.LocalDate.now(app.zone).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val counted = app.activities.filter { a ->
        val d = a.start.atZone(app.zone).toLocalDate()
        c.counts(a.kind) && !d.isBefore(c.startDate) && !d.isBefore(monday) && (c.startedAt == null || !a.start.isBefore(c.startedAt)) && !c.isPausedAt(a.start)
    }.sortedBy { it.start }
    Text(if (open) "Hide what's counted" else "What's counted this week (${counted.size})", style = MaterialTheme.typography.labelLarge,
        color = Palette.Volt, modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp))
    if (!open) return
    if (counted.isEmpty()) Text("No ${listOfNotNull(if (c.includeWalks) "walks" else null, if (c.includeRuns) "runs" else null).joinToString(" or ")} " +
        "since Monday${if (c.startedAt != null) " (or since you started this challenge)" else ""}.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    counted.forEach { a ->
        Column(Modifier.padding(vertical = 2.dp)) {
            Row {
                Text(a.start.atZone(app.zone).format(java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a")) + " · " +
                    a.kind.name.lowercase().replaceFirstChar { it.uppercase() } + (a.distanceMeters?.let { " %.1f km".format(it / 1000) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(a.ascentMeters?.let { "%.0f m".format(it) } ?: "—", style = MaterialTheme.typography.titleSmall,
                    color = if (a.ascentMeters != null) Palette.Volt else Palette.TextDim)
            }
            Text(app.climbSource(a), style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        }
    }
    if (app.terrainEnabled == true && (app.terrainFailures > 0 || counted.any { app.climbSource(it).startsWith("terrain lookup") }))
        GhostButton(if (app.terrainBusy) "Checking…" else "Retry terrain lookups", enabled = !app.terrainBusy, onClick = { app.fetchTerrain() })
    if (!c.includeWalks) Text("This challenge counts runs only. Walks aren't included.", style = MaterialTheme.typography.labelSmall, color = Palette.Warn)
}
