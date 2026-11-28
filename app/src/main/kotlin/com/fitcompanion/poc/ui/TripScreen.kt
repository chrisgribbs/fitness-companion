package com.fitcompanion.poc.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.fitcompanion.core.Splits
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.ui.theme.ChoiceChip
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.VoltButton
import kotlinx.coroutines.launch

/**
 * Plan a walk or run between two places: route along real streets, hills from the terrain map, and the time,
 * steps and climb estimated in code at your own pace. The coach can then turn it into a workout for your day.
 */
@Composable
fun TripScreen(app: AppController, onClose: () -> Unit, onAskCoach: (String) -> Unit) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    val t = app.trip
    var from by remember { mutableStateOf(t?.request?.from ?: "") }
    var to by remember { mutableStateOf(t?.request?.to ?: "") }
    var run by remember { mutableStateOf(t?.request?.run ?: false) }
    var round by remember { mutableStateOf(t?.request?.roundTrip ?: false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fromVoice = rememberVoiceInput("Where are you starting from?", { from = it })
    val toVoice = rememberVoiceInput("Where are you going?", { to = it })

    fun plan(pick: com.fitcompanion.poc.data.TripPlanner.Place? = null) {
        busy = true; error = null
        scope.launch {
            runCatching { app.planTrip(AppController.TripRequest(from.trim(), to.trim(), "", run, round), toPick = pick) }
                .onFailure { error = it.message }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PLAN A WALK", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Palette.Text) }
            }
            Text("Type or say where you're going. You can also just tell the coach, e.g. \"I'm at 83 Burleigh Street, Spring Hill, and want to walk to Woolworths at the end of Queen Street\".",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        item {
            OutlinedTextField(from, { from = it }, Modifier.fillMaxWidth(), label = { Text("From (leave empty for where you are)") }, singleLine = true,
                trailingIcon = { IconButton(onClick = fromVoice) { Icon(Icons.Rounded.Mic, "Say it", tint = Palette.Volt) } })
        }
        item {
            OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth(), label = { Text("To") }, singleLine = true,
                trailingIcon = { IconButton(onClick = toVoice) { Icon(Icons.Rounded.Mic, "Say it", tint = Palette.Volt) } })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Walk", !run) { run = false }; ChoiceChip("Run", run) { run = true }
                Spacer(Modifier.width(8.dp))
                ChoiceChip("One way", !round) { round = false }; ChoiceChip("There and back", round) { round = true }
            }
        }
        item { VoltButton(if (busy) (app.tripStatus ?: "Planning…") else "Plan it", onClick = { plan() }, enabled = !busy && to.isNotBlank(), modifier = Modifier.fillMaxWidth()) }
        error?.let { item { Text(it, color = Palette.Warn, style = MaterialTheme.typography.bodyMedium) } }

        if (t != null) {
            val e = t.estimate
            item {
                Eyebrow(if (t.request.run) "Your run" else "Your walk")
                Text("${t.from.name} → ${t.to.name}" + if (t.request.roundTrip) " and back" else "", style = MaterialTheme.typography.titleLarge)
                Text(t.to.label, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2)
            }
            item { Box(Modifier.clip(RoundedCornerShape(18.dp))) { RouteMap(t.input.route) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Big("Distance", "%.2f".format(e.meters / 1000), "km")
                    Big("Time", "%.0f".format(e.minutes), "min")
                    Big("Climb", e.climbM?.let { "%.0f".format(it) } ?: "?", "m")
                }
            }
            item {
                Text("About %,d steps · effort %s · average %s/km".format(e.steps, e.effort, Splits.formatPace(e.avgPaceSecPerKm)),
                    style = MaterialTheme.typography.bodyMedium)
                Text("Estimates: your usual flat pace ${Splits.formatPace(t.input.flatPaceSecPerKm)}/km (${t.input.paceSource}), slowed for hills; steps from a typical stride.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            if (e.steepest.isNotEmpty()) item {
                Eyebrow("Steepest bits")
                e.steepest.forEach { s -> Text("• ${s.street}: up %.0f m over %.0f m (%+.1f%%)".format(s.climbM, s.meters, s.gradePct), style = MaterialTheme.typography.bodyMedium) }
            }
            if (t.toOptions.size > 1) item {
                Eyebrow("Not the right place?")
                t.toOptions.forEach { o ->
                    val chosen = o == t.to
                    Text((if (chosen) "✓ " else "") + o.label, style = MaterialTheme.typography.bodySmall,
                        color = if (chosen) Palette.Volt else Palette.Text, maxLines = 2,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Surface)
                            .clickable(enabled = !chosen && !busy) { plan(o) }.padding(12.dp))
                    Spacer(Modifier.padding(2.dp))
                }
            }
            item {
                VoltButton("Ask the coach for a workout plan", onClick = {
                    onAskCoach("Make this into a workout for my day: " + (if (t.request.run) "a run" else "a walk") + " from ${t.from.name} to ${t.to.name}" +
                        (if (t.request.roundTrip) " and back" else "") + ".")
                }, modifier = Modifier.fillMaxWidth())
                Text("The coach sees the place names and the numbers above, not your location history or health data beyond its usual summary.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
            }
            item {
                GhostButton("Start this ${if (t.request.run) "run" else "walk"} now (GPS)", onClick = { onClose(); app.openLive() }, modifier = Modifier.fillMaxWidth())
                Text("Route and places © OpenStreetMap contributors; routing by FOSSGIS. Place names you type are sent to OpenStreetMap's search.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
            }
        }
    }
}

@Composable
private fun Big(label: String, value: String, unit: String) = Column {
    Eyebrow(label)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.width(4.dp))
        Text(unit, style = MaterialTheme.typography.titleMedium, color = Palette.TextDim, modifier = Modifier.padding(bottom = 6.dp))
    }
}
