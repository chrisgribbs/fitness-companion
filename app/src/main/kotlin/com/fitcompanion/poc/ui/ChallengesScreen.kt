package com.fitcompanion.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fitcompanion.core.ChallengeDefinition
import com.fitcompanion.core.ChallengeMetric
import com.fitcompanion.core.ChallengeProgress
import com.fitcompanion.core.Everest
import com.fitcompanion.core.JourneyRoute
import com.fitcompanion.core.WaypointCheckStatus
import com.fitcompanion.poc.AppController
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

fun meters(m: Double): String = if (m < 10_000) "%,.0f m".format(m) else "%,.1f km".format(m / 1000)

@Composable
fun ChallengesScreen(app: AppController) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Challenges", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Text("Demo data", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Switch(checked = app.useDemoData, onCheckedChange = { on -> scope.launch { app.setDemo(on) } })
            }
            if (app.useDemoData) DemoBanner()
            Text(
                app.lastSynced?.let { "Last synced ${it.format(DateTimeFormatter.ofPattern("d MMM, h:mm a"))}" } ?: "Not synced yet",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { app.refresh() } }, enabled = !app.busy) { Text("Refresh") }
                OutlinedButton(onClick = { creating = !creating }) { Text(if (creating) "Close" else "New challenge") }
            }
            app.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        }

        if (creating) item { CreateChallenge(app) { creating = false } }

        if (app.challenges.isEmpty() && !creating) item {
            Text("No challenges yet. Tap New challenge — try the Everest climb, or describe a journey like " +
                "\"walk the coast from Brisbane to the Gold Coast\".")
        }

        items(app.challenges, key = { it.id }) { c -> ChallengeCard(app, c, app.progressOf(c)) }
    }
}

@Composable
private fun DemoBanner() {
    Box(Modifier.fillMaxWidth().background(Color(0xFFFFE08A)).padding(8.dp)) {
        Text("DEMO DATA — made-up activities, not your Fitbit results", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ChallengeCard(app: AppController, c: ChallengeDefinition, p: ChallengeProgress) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleLarge)
            if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall)
            Text(
                listOfNotNull(if (c.includeRuns) "runs" else null, if (c.includeWalks) "walks" else null).joinToString(" + ") +
                    " · since ${c.startDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}" + if (c.createdByAi) " · designed with AI" else "",
                style = MaterialTheme.typography.labelSmall,
            )
            if (p.includesDemoData) DemoBanner()

            when {
                c.metric == ChallengeMetric.ASCENT -> {
                    MountainGraphic(p, c.milestones)
                    val pctOfGoalThisWeek = p.thisWeekMeters / c.goalMeters * 100
                    Text("This week you climbed ${meters(p.thisWeekMeters)} — ${"%.1f".format(pctOfGoalThisWeek)}% of ${if (c.goalMeters == Everest.HEIGHT_METERS) "Everest" else "the goal"}.",
                        style = MaterialTheme.typography.titleMedium)
                }
                c.route.size >= 2 -> {
                    val route = JourneyRoute(c.route)
                    JourneyMap(route, p.totalMeters)
                    val pos = route.positionAt(p.totalMeters)
                    Text(
                        if (pos.finished) "You've arrived at ${c.route.last().name}! 🎉"
                        else "Past ${c.route[pos.lastPassedIndex].name} — ${meters(pos.metersToNextWaypoint)} to ${c.route[pos.lastPassedIndex + 1].name}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Route is approximate: straight lines between ${c.route.size} waypoints. Map © OpenStreetMap contributors, OpenFreeMap.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            LinearProgressIndicator(progress = { p.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("${meters(p.totalMeters)} of ${meters(p.goalMeters)} ${if (c.metric == ChallengeMetric.ASCENT) "climbed" else "covered"} · " +
                "${"%.1f".format(p.percent)}% · ${meters(p.remainingMeters)} to go")
            Text("This week ${meters(p.thisWeekMeters)} · this month ${meters(p.thisMonthMeters)}", style = MaterialTheme.typography.bodySmall)
            WeeklyBars(p)
            p.next?.let { Text("Next: ${it.name} in ${meters(it.atMeters - p.totalMeters)}") }
            if (p.reached.isNotEmpty()) Text("Reached: ${p.reached.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
            p.completedOn?.let { Text("Completed on $it! 🎉", style = MaterialTheme.typography.titleMedium) }
            p.estimate?.let {
                Text("Estimate: at your recent ${meters(it.metersPerDay * 7)} a week, you'd finish around " +
                    "${it.date.format(DateTimeFormatter.ofPattern("d MMM yyyy"))} (based on the last ${it.basisDays} days).",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (p.activitiesMissingData > 0) Text(
                "${p.activitiesMissingData} matching activit${if (p.activitiesMissingData == 1) "y has" else "ies have"} no " +
                    "${if (c.metric == ChallengeMetric.ASCENT) "elevation" else "distance"} data and aren't counted.",
                style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { app.resetStart(c) }) { Text("Restart from today") }
                TextButton(onClick = { if (confirmDelete) app.delete(c.id) else confirmDelete = true }) {
                    Text(if (confirmDelete) "Tap again to delete" else "Delete")
                }
            }
        }
    }
}

@Composable
private fun WeeklyBars(p: ChallengeProgress) {
    val max = (p.weekly.maxOfOrNull { it.meters } ?: 0.0).coerceAtLeast(1.0)
    Column {
        Text("Last 8 weeks", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            p.weekly.forEachIndexed { i, wk ->
                Box(Modifier.weight(1f).height((50 * (wk.meters / max)).coerceAtLeast(2.0).dp)
                    .background(if (i == p.weekly.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
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

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Climb Everest", style = MaterialTheme.typography.titleMedium)
            Text("Add up your climbing until you reach 8,849 m.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(runs, { runs = it }); Text("Runs"); Spacer(Modifier.width(12.dp))
                Checkbox(walks, { walks = it }); Text("Walks")
            }
            Button(onClick = { app.addEverest(runs, walks); onDone() }, enabled = runs || walks) { Text("Start the Everest climb") }

            Spacer(Modifier.height(8.dp))
            Text("Describe your own", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Brisbane → Gold Coast coast walk" to "A coastal walk from Brisbane to Coolangatta on the Gold Coast, following the coast where possible. Walks only.",
                    "Sunshine Coast" to "Walk the Sunshine Coast coastline from Caloundra to Noosa Heads, passing the main beaches. Walks only.",
                    "California → New York" to "Walk across America from San Francisco, California to New York City, roughly along Interstate 80. Runs and walks.",
                ).forEach { (label, prompt) -> FilterChip(selected = text == prompt, onClick = { text = prompt }, label = { Text(label) }) }
            }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("e.g. \"Climb Kilimanjaro twice, runs only\"") })
            Text("Only this description is sent to OpenRouter — none of your health or location data.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { scope.launch { app.designChallenge(text) } }, enabled = text.isNotBlank() && !app.busy && app.hasKey) {
                Text("Design it with AI")
            }
            if (!app.hasKey) Text("Add your OpenRouter key in the AI tab to use this.", style = MaterialTheme.typography.bodySmall)

            app.proposal?.let { prop -> Proposal(app, prop, onDone) }
        }
    }
}

@Composable
private fun Proposal(app: AppController, prop: AppController.ChallengeProposal, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    Spacer(Modifier.height(8.dp))
    if (prop.definition == null) {
        Text("The AI's challenge had problems:", color = MaterialTheme.colorScheme.error)
        prop.errors.forEach { Text("• $it") }
        return
    }
    val d = prop.definition
    Text("Proposed: ${d.name}", style = MaterialTheme.typography.titleMedium)
    if (d.description.isNotBlank()) Text(d.description)
    Text("Goal: ${meters(d.goalMeters)} ${if (d.metric == ChallengeMetric.ASCENT) "of climbing" else "(measured by the app)"} · " +
        listOfNotNull(if (d.includeRuns) "runs" else null, if (d.includeWalks) "walks" else null).joinToString(" + "))
    prop.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }

    if (d.route.size >= 2) {
        JourneyMap(JourneyRoute(d.route), 0.0)
        if (!prop.checked) {
            Text("The AI placed ${d.route.size} waypoints. Check them against OpenStreetMap before saving? " +
                "(Takes about ${d.route.size + 1} seconds; only place names are sent.)", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { scope.launch { app.checkPlaces() } }, enabled = !app.busy) { Text("Check places") }
            app.placeProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else {
            prop.checks.forEach { c ->
                val mark = when (c.status) {
                    WaypointCheckStatus.VERIFIED -> "✓"
                    WaypointCheckStatus.MOVED -> "⚠ moved ${"%.0f".format(c.offsetKm)} km to the real place"
                    WaypointCheckStatus.NOT_FOUND -> "? not found — kept AI position"
                }
                Text("$mark  ${c.name}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Place data © OpenStreetMap contributors.", style = MaterialTheme.typography.labelSmall)
        }
    } else if (d.milestones.isNotEmpty()) {
        Text("Milestones: " + d.milestones.joinToString { "${it.name} (${meters(it.atMeters)})" }, style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { app.acceptProposal(); onDone() }, enabled = !app.busy) { Text("Save challenge") }
        TextButton(onClick = { app.proposal = null }) { Text("Discard") }
    }
}
