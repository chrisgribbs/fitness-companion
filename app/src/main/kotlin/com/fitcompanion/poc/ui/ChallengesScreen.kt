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

        items(app.challenges, key = { it.id }) { c -> ChallengeCard(app, c, app.progressOf(c), onOpenActivity) }
    }
}

@Composable
private fun ChallengeCard(app: AppController, c: ChallengeDefinition, p: ChallengeProgress, onOpenActivity: (ActivityRecord) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val climb = c.metric == ChallengeMetric.ASCENT
    val brush = if (climb) Palette.ClimbGlow else Palette.VoltGlow
    val accent = if (climb) Palette.Climb else Palette.Walk
    val jv = app.journalVersion
    val counted = remember(app.activities, c) {
        app.activities.filter { c.counts(it.kind) && !it.start.atZone(app.zone).toLocalDate().isBefore(c.startDate) }
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
                " · since ${c.startDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}",
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

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Restart", onClick = { app.resetStart(c) })
                GhostButton(if (confirmDelete) "Tap to confirm" else "Delete", color = Palette.Heart,
                    onClick = { if (confirmDelete) app.delete(c.id) else confirmDelete = true })
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
            if (!app.hasKey) Text("Add your OpenRouter key in the Coach tab to use this.", style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
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
        if (!climb) Text("Distance measured by the app along the waypoints.", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        prop.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }

        if (d.route.size >= 2) {
            Box(Modifier.clip(RoundedCornerShape(16.dp))) { JourneyMap(JourneyRoute(d.route), 0.0) }
            if (!prop.checked) {
                Text("Check the AI's ${d.route.size} waypoints against OpenStreetMap before saving (about ${d.route.size + 1} s; only place names are sent).",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                GhostButton("Check places", onClick = { scope.launch { app.checkPlaces() } }, enabled = !app.busy, icon = Icons.Rounded.Place)
                app.placeProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Volt) }
            } else {
                prop.checks.forEach { c ->
                    val (mark, color) = when (c.status) {
                        WaypointCheckStatus.VERIFIED -> "✓" to Palette.Good
                        WaypointCheckStatus.MOVED -> "⚠ moved ${"%.0f".format(c.offsetKm)} km" to Palette.Warn
                        WaypointCheckStatus.NOT_FOUND -> "? not found" to Palette.TextDim
                    }
                    Row { Text(mark, color = color, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(8.dp)); Text(c.name, style = MaterialTheme.typography.bodySmall) }
                }
                Text("Place data © OpenStreetMap contributors.", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
            }
        } else if (d.milestones.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.milestones.forEach { Tag("${it.name} · ${meters(it.atMeters)}", Palette.Climb) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VoltButton("Save challenge", onClick = { app.acceptProposal(); onDone() }, enabled = !app.busy)
            GhostButton("Discard", onClick = { app.proposal = null })
        }
    }
}
