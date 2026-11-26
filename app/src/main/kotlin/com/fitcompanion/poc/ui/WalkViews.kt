package com.fitcompanion.poc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.data.TrailPhotos
import com.fitcompanion.poc.data.WalkPresets
import com.fitcompanion.poc.ui.theme.ChoiceChip
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.SportCard
import com.fitcompanion.poc.ui.theme.Tag
import com.fitcompanion.poc.ui.theme.VoltButton
import kotlinx.coroutines.launch

/** One built-in walk, ready to start as a virtual journey. */
@Composable
fun PresetCard(app: AppController, p: WalkPresets.Preset, onStarted: () -> Unit) {
    var runs by remember { mutableStateOf(false) }
    var walks by remember { mutableStateOf(true) }
    val already = app.challenges.any { it.presetId == p.id }
    SportCard(brush = Palette.VoltGlow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Hiking, null, tint = Palette.Bg)
            Spacer(Modifier.width(8.dp))
            Eyebrow("Sunshine Coast walk", Palette.Bg, Modifier.weight(1f))
            if (already) Tag("STARTED", Palette.Bg)
        }
        Text(p.name.uppercase(), style = MaterialTheme.typography.headlineMedium, color = Palette.Bg)
        Text(p.description, style = MaterialTheme.typography.bodyMedium, color = Palette.Bg)
        Text("${kmNumber(p.routeMeters)} km along the mapped path · ${p.milestones.size} landmarks" +
            (if (p.officialKm > 0) " · officially about ${"%.1f".format(p.officialKm)} km" else ""),
            style = MaterialTheme.typography.titleSmall, color = Palette.Bg)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Walks", walks, { walks = !walks }, accent = Palette.Bg)
            ChoiceChip("Runs", runs, { runs = !runs }, accent = Palette.Bg)
        }
        Text("Your Fitbit walks${if (runs) " and runs" else ""} from today move you along the trail, wherever you actually walk.",
            style = MaterialTheme.typography.bodySmall, color = Palette.Bg)
        Box(Modifier.clip(RoundedCornerShape(50)).background(Palette.Bg)) {
            GhostButton(if (already) "Start again (new copy)" else "Start this walk", icon = Icons.Rounded.Flag, color = Palette.Volt,
                enabled = runs || walks, onClick = { app.addPreset(p, runs, walks); onStarted() })
        }
    }
}

/** "What it looks like here": a nearby photo for a point on the trail. */
@Composable
fun TrailPhotoPanel(app: AppController, lat: Double, lon: Double, label: String) {
    var photo by remember(lat, lon) { mutableStateOf<TrailPhotos.Photo?>(null) }
    var loading by remember(lat, lon) { mutableStateOf(false) }
    var asked by remember(lat, lon) { mutableStateOf(false) }
    var tried by remember(lat, lon) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load(streetView: Boolean) = scope.launch {
        asked = true; loading = true
        photo = app.photoAt(lat, lon, streetView); loading = false; tried = true
    }
    // Rounded to ~100 m so a tiny step forward doesn't fetch a new picture.
    LaunchedEffect("%.3f,%.3f".format(lat, lon)) { load(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("What it looks like here · $label")
        val p = photo
        if (p != null) {
            Image(p.bitmap.asImageBitmap(), p.title ?: label, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(18.dp)))
            Text(listOfNotNull(
                p.distanceMeters?.let { "About ${meters(it)} from your spot" },
                if (p.source == TrailPhotos.Source.STREET_VIEW) "Google Street View" else p.title,
            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Text(p.credit, style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
            if (app.hasMapsKey && p.source == TrailPhotos.Source.WIKIMEDIA)
                GhostButton("Show Street View instead", icon = Icons.Rounded.Photo, onClick = { load(true) }, enabled = !loading)
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 7f).clip(RoundedCornerShape(18.dp)).background(Palette.SurfaceHigh).padding(16.dp),
                contentAlignment = Alignment.Center) {
                Text(when {
                    loading || !asked -> "Finding a photo near $label…"
                    else -> "No public photo found near this spot." + if (!app.hasMapsKey) " Add a Google Street View key in Setup for more coverage." else ""
                }, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            }
        }
        Text("To find a photo, only this point on the trail is sent (to Wikimedia Commons${if (app.hasMapsKey) " or Google" else ""}), never your real location or health data.",
            style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
    }
}

/** Optional: your own Google Street View key, for photos where Wikimedia has none. */
@Composable
fun MapsKeyCard(app: AppController) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    SportCard {
        Eyebrow("Trail photos")
        Text(if (app.hasMapsKey) "Street View is on" else "Free photos are on", style = MaterialTheme.typography.titleLarge)
        Text("Walks show public photos from Wikimedia Commons (free, no setup). Optionally add your own Google key to fill gaps with Street View.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        app.mapsKeyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Not")) Palette.Heart else Palette.Good) }
        if (app.hasMapsKey) {
            GhostButton("Remove Google key", color = Palette.Heart, onClick = { app.removeMapsKey() })
        } else if (!open) {
            GhostButton("Set up Street View (optional)", onClick = { open = true })
        } else {
            listOf(
                "1. In Google Cloud Console (the same project as before is fine), go to APIs & Services → Library and enable \"Street View Static API\".",
                "2. Billing must be on for the project. Google gives 10,000 Street View images a month free; this app uses a handful a day.",
                "3. APIs & Services → Credentials → Create credentials → API key.",
                "4. Edit the key: Application restriction \"Android apps\", add package com.fitcompanion.poc with SHA-1 69:CE:07:EE:12:92:C7:22:4D:7E:1F:44:AB:D7:5E:36:28:70:71:83.",
                "5. API restriction: \"Restrict key\" → only Street View Static API.",
                "6. Optional safety net: Quotas → set Street View Static API to e.g. 300 requests per day.",
                "7. Copy the key and paste it below yourself. It's encrypted on this phone and only ever sent to Google.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text("Paste your API key", color = Palette.TextDim) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Volt, unfocusedBorderColor = Palette.Outline),
                shape = RoundedCornerShape(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VoltButton("Test & save", onClick = { scope.launch { app.saveMapsKey(key); if (app.hasMapsKey) { key = ""; open = false } } }, enabled = key.isNotBlank())
                GhostButton("Cancel", onClick = { open = false; key = "" })
            }
        }
    }
}
