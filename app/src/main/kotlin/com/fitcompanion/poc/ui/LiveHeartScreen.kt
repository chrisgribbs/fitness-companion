package com.fitcompanion.poc.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.fitcompanion.core.HeartZones
import com.fitcompanion.core.LiveSessionSummary
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.LiveHeartService
import com.fitcompanion.poc.data.LiveHeart
import com.fitcompanion.poc.ui.theme.ChoiceChip
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.SportCard
import com.fitcompanion.poc.ui.theme.StatusLine
import com.fitcompanion.poc.ui.theme.VoltButton
import java.time.Instant
import java.time.format.DateTimeFormatter

fun zoneColor(z: Int): Color = when (z) { 1 -> Palette.Sleep; 2 -> Palette.Walk; 3 -> Palette.Volt; 4 -> Palette.Run; 5 -> Palette.Heart; else -> Palette.TextDim }

private fun btPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun mmss(sec: Long) = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

/** Live heart rate from your band, with zones, a target-zone alert and a session summary. */
@Composable
fun LiveHeartScreen(app: AppController, initialTarget: Int? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    LiveHeart.init(context)
    fun granted() = btPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var hasPermission by remember { mutableStateOf(granted()) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPermission = granted() }
    var lastSummary by remember { mutableStateOf<LiveSessionSummary?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }

    // Zones: resting HR from your Fitbit (7-day average), max from your age or your own value.
    val resting = app.restingHrForZones
    val max = app.maxHr
    LaunchedEffect(resting, max) { if (max != null) LiveHeart.zonesFor(resting ?: 60, max) }
    LaunchedEffect(Unit) { if (initialTarget != null && !LiveHeart.recording) LiveHeart.targetZone = initialTarget }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Favorite, null, tint = Palette.Heart)
                Spacer(Modifier.width(8.dp))
                Text("LIVE WORKOUT", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Palette.Text) }
            }

            if (max == null) MaxHrCard(app)

            when {
                !hasPermission -> SportCard {
                    Eyebrow("Connect your band")
                    Text("The app reads only the heart rate your band shares over Bluetooth (the standard heart-rate signal). Android will ask to allow Nearby devices.",
                        style = MaterialTheme.typography.bodyMedium)
                    VoltButton("Allow Bluetooth", icon = Icons.Rounded.Bluetooth, onClick = {
                        val p = btPermissions() + (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
                        askPerms.launch(p)
                    })
                }
                LiveHeart.status != LiveHeart.Status.CONNECTED && LiveHeart.status != LiveHeart.Status.RECONNECTING -> ConnectCard()
                else -> LivePanel(now)
            }

            WorkoutCard(now) { s -> lastSummary = s }

            lastSummary?.let { SummaryCard(it, "Workout saved") }

            val recent = remember(lastSummary) { LiveHeart.summaries() }
            if (recent.isNotEmpty()) SportCard {
                Eyebrow("Recent live workouts")
                recent.take(5).forEach { s ->
                    Row {
                        Text(Instant.ofEpochMilli(s.startEpochMs).atZone(app.zone).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(listOfNotNull(mmss(s.durationSec.toLong()), s.distanceMeters?.let { "%.2f km".format(it / 1000) },
                            if (s.avg > 0) "avg ${s.avg} bpm" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    }
                }
            }

            Text("Zones use your heart-rate reserve (Karvonen method): resting ${resting ?: "~60 (no Fitbit data yet)"} bpm, max ${max ?: "?"} bpm" +
                (if (app.maxHrIsEstimate) " (estimated as 208 − 0.7 × age; Tanaka et al. 2001)" else " (your own)") +
                ". Wrist readings can lag or spike during fast changes. Not medical advice.",
                style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        }
    }
}

@Composable
private fun MaxHrCard(app: AppController) {
    var age by remember { mutableStateOf("") }
    var max by remember { mutableStateOf("") }
    SportCard {
        Eyebrow("Set up your zones", Palette.Warn)
        Text("Enter your age, or your maximum heart rate if you know it from a test.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(age, { age = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), singleLine = true, label = { Text("Age") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Volt, unfocusedBorderColor = Palette.Outline))
            OutlinedTextField(max, { max = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), singleLine = true, label = { Text("Max HR (optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Volt, unfocusedBorderColor = Palette.Outline))
        }
        VoltButton("Save", enabled = (age.toIntOrNull() ?: 0) in 10..100 || (max.toIntOrNull() ?: 0) in 120..230, onClick = {
            app.setHeartProfile(age.toIntOrNull(), max.toIntOrNull()?.takeIf { it in 120..230 })
        })
    }
}

@Composable
private fun ConnectCard() {
    SportCard {
        Eyebrow("Connect your band")
        StatusLine(LiveHeart.statusText, if (LiveHeart.status == LiveHeart.Status.ERROR) Palette.Warn else Palette.Volt)
        Text("First, in the Google Health app: Devices → your Fitbit Air → Share heart rate → turn on Always visible.",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        val saved = LiveHeart.savedAddress
        if (saved != null && LiveHeart.status != LiveHeart.Status.CONNECTING)
            VoltButton("Connect to ${LiveHeart.savedName ?: "my band"}", icon = Icons.Rounded.Bluetooth, modifier = Modifier.fillMaxWidth(),
                onClick = { LiveHeart.connect(saved, LiveHeart.savedName ?: "your band") })
        if (LiveHeart.status == LiveHeart.Status.SCANNING) GhostButton("Stop looking", onClick = { LiveHeart.stopScan() })
        else if (LiveHeart.status != LiveHeart.Status.CONNECTING)
            GhostButton(if (saved == null) "Find my band" else "Find a different band", icon = Icons.Rounded.Bluetooth, onClick = { LiveHeart.scan() })
        LiveHeart.found.forEach { f ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).clickable { LiveHeart.connect(f.address, f.name) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Favorite, null, tint = Palette.Heart)
                Spacer(Modifier.width(10.dp))
                Text(f.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("CONNECT", style = MaterialTheme.typography.labelLarge, color = Palette.Volt)
            }
        }
    }
}

@Composable
private fun LivePanel(now: Long) {
    val bpm = LiveHeart.bpm
    val stale = now - LiveHeart.lastReadingAt > 10_000
    val zones = LiveHeart.zones
    val zone = if (bpm != null && zones.isNotEmpty()) HeartZones.zoneOf(bpm, zones) else 0
    val color = zoneColor(zone)

    SportCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(LiveHeart.statusText, if (LiveHeart.status == LiveHeart.Status.RECONNECTING) Palette.Warn else Palette.Good, Modifier.weight(1f))
            if (LiveHeart.recording) Text(mmss((now - LiveHeart.sessionStart) / 1000), style = MaterialTheme.typography.titleLarge)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (bpm == null || stale) "--" else "$bpm", fontSize = 96.sp, fontWeight = FontWeight.Bold, color = if (stale) Palette.TextDim else color,
                style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.width(8.dp))
            Text("bpm", style = MaterialTheme.typography.titleLarge, color = Palette.TextDim, modifier = Modifier.padding(bottom = 18.dp))
        }
        if (stale) Text("Waiting for heart rate… keep the band snug on your wrist.", style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
        else if (LiveHeart.contact == false) Text("The band isn't reading your skin: tighten it a little.", style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
        if (zones.isNotEmpty()) {
            val z = zones.firstOrNull { it.number == zone }
            Text(if (z != null) "ZONE ${z.number} · ${z.name.uppercase()}" else "BELOW ZONE 1", style = MaterialTheme.typography.titleMedium, color = color)
            z?.let { Text(it.feel, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
            ZoneBar(zone)
        }
        LiveHeart.alertText?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Palette.Warn) }
    }

    if (zones.isNotEmpty()) SportCard {
        Eyebrow("Target zone")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Off", LiveHeart.targetZone == null, { LiveHeart.targetZone = null })
            zones.forEach { z -> ChoiceChip("${z.number} ${z.name}", LiveHeart.targetZone == z.number, { LiveHeart.targetZone = z.number }, accent = zoneColor(z.number)) }
        }
        LiveHeart.targetZone?.let { t -> zones.firstOrNull { it.number == t }?.let { z ->
            Text("${z.low}–${z.high} bpm · ${z.feel}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Buzz and beep if I'm out of zone for 15 s", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(LiveHeart.alertsOn, { LiveHeart.alertsOn = it }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Volt, checkedThumbColor = Palette.Bg))
        }
    }

    if (!LiveHeart.recording) GhostButton("Disconnect band", onClick = { LiveHeart.disconnect() })
}

/** Start/finish, GPS route and live stats. Works with the band, with GPS only, or both. */
@Composable
private fun WorkoutCard(now: Long, onStopped: (LiveSessionSummary?) -> Unit) {
    val context = LocalContext.current
    fun hasLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val connected = LiveHeart.status == LiveHeart.Status.CONNECTED || LiveHeart.status == LiveHeart.Status.RECONNECTING
    fun start(gps: Boolean) { LiveHeart.startSession(gps); LiveHeartService.start(context); onStopped(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { start(LiveHeart.gpsOn && hasLocation()) }

    if (!LiveHeart.recording) {
        SportCard {
            Eyebrow("Workout")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Record my route with the phone's GPS", style = MaterialTheme.typography.bodyMedium)
                    Text("Map, distance, pace and climb. Stays on this phone.", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                }
                Switch(LiveHeart.gpsOn, { LiveHeart.gpsOn = it }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Volt, checkedThumbColor = Palette.Bg))
            }
            VoltButton("Start workout", icon = Icons.Rounded.PlayArrow, modifier = Modifier.fillMaxWidth(), enabled = connected || LiveHeart.gpsOn, onClick = {
                if (LiveHeart.gpsOn && !hasLocation()) askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else start(LiveHeart.gpsOn)
            })
            Text(when {
                connected && LiveHeart.gpsOn -> "Heart rate from your band, route from your phone. Keeps going with the screen off."
                connected -> "Heart rate only. Keeps going with the screen off."
                LiveHeart.gpsOn -> "GPS only. Connect your band above to add heart rate and zones."
                else -> "Connect your band or turn on GPS to start."
            } + " It doesn't replace your Fitbit's own workout record.", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        }
        return
    }

    val pts = LiveHeart.gpsPoints
    SportCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Recording", Palette.Heart, Modifier.weight(1f))
            Text(mmss((now - LiveHeart.sessionStart) / 1000), style = MaterialTheme.typography.headlineMedium)
        }
        if (LiveHeart.gpsOn) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Mini("Distance", "%.2f km".format(LiveHeart.gpsDistance / 1000))
                Mini("Pace", LiveHeart.currentPace()?.let { com.fitcompanion.core.Splits.formatPace(it) + "/km" } ?: "--")
                LiveHeart.samples.takeIf { it.isNotEmpty() }?.let { Mini("Avg HR", "${it.map { s -> s.second }.average().toInt()}") }
            }
            LiveHeart.gpsStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (it.startsWith("GPS")) Palette.TextDim else Palette.Warn) }
            if (pts.isNotEmpty()) RouteMap(pts.map { it.latitude!! to it.longitude!! }, Modifier.clip(RoundedCornerShape(18.dp)),
                colors = LiveHeart.gpsColors, follow = true, height = 260.dp)
            if (LiveHeart.zones.isNotEmpty() && pts.size > 1) Text("Route colour shows your heart-rate zone.", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        }
        if (LiveHeart.samples.size > 2) { Eyebrow("Heart rate, last 10 minutes"); HrChart(LiveHeart.samples, LiveHeart.zones, now) }
        VoltButton("Finish workout", icon = Icons.Rounded.Stop, modifier = Modifier.fillMaxWidth(), onClick = {
            onStopped(LiveHeart.stopSession()); LiveHeartService.stop(context)
        })
    }
}

@Composable
private fun Mini(label: String, value: String) {
    Column { Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextDim); Text(value, style = MaterialTheme.typography.titleLarge) }
}

@Composable
private fun ZoneBar(zone: Int) {
    Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..5).forEach { z ->
            Box(Modifier.weight(1f).height(if (z == zone) 14.dp else 8.dp).clip(RoundedCornerShape(50))
                .background(zoneColor(z).copy(alpha = if (z == zone) 1f else 0.3f)))
        }
    }
}

@Composable
private fun HrChart(samples: List<Pair<Long, Int>>, zones: List<com.fitcompanion.core.HrZone>, now: Long) {
    val window = 10 * 60_000L
    val pts = samples.filter { it.first >= now - window }
    val lo = ((pts.minOfOrNull { it.second } ?: 60) - 10).coerceAtLeast(40)
    val hi = ((pts.maxOfOrNull { it.second } ?: 160) + 10).coerceAtMost(230)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        fun y(b: Int) = size.height - (b - lo).toFloat() / (hi - lo) * size.height
        fun x(t: Long) = (1f - (now - t).toFloat() / window) * size.width
        zones.forEach { z ->
            val top = y(z.high.coerceAtMost(hi)); val bottom = y(z.low.coerceAtLeast(lo))
            if (bottom > top) drawRect(zoneColor(z.number).copy(alpha = 0.10f), Offset(0f, top), Size(size.width, bottom - top))
        }
        if (pts.size >= 2) {
            val path = Path()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.first), y(p.second)) else path.lineTo(x(p.first), y(p.second)) }
            drawPath(path, Palette.Heart, style = Stroke(width = 5f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun SummaryCard(s: LiveSessionSummary, title: String) {
    SportCard {
        Eyebrow(title, Palette.Good)
        Text(listOfNotNull(mmss(s.durationSec.toLong()), s.distanceMeters?.let { "%.2f km".format(it / 1000) },
            if (s.avg > 0) "average ${s.avg} bpm · max ${s.max} bpm" else null).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
        s.routeId?.let { id -> LiveHeart.route(id)?.let { r -> RouteDetails(r, null) } }
        (1..5).forEach { z ->
            val secs = s.secondsInZone.getOrElse(z) { 0 }
            if (secs > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(12.dp).height(12.dp).clip(RoundedCornerShape(50)).background(zoneColor(z)))
                Spacer(Modifier.width(8.dp))
                Text("Zone $z", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(70.dp))
                Text(mmss(secs.toLong()) + " (%.0f%%)".format(secs * 100.0 / s.durationSec.coerceAtLeast(1)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        s.targetZone?.let { Text("In your target zone $it for ${mmss(s.secondsInTarget.toLong())}.", style = MaterialTheme.typography.bodyMedium, color = Palette.Volt) }
        s.rmssdMs?.let { Text("Beat-to-beat variability during the workout (RMSSD): %.0f ms. Not comparable with your overnight HRV.".format(it),
            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
    }
}
