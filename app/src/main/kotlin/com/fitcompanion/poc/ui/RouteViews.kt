package com.fitcompanion.poc.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import com.fitcompanion.core.ActivityRecord
import com.fitcompanion.core.Splits
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.data.RouteInfo
import com.fitcompanion.poc.data.RouteLookup
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.VoltButton

/** Map, GPS distance, splits and estimated climb for one workout. */
@Composable
fun RouteSection(app: AppController, a: ActivityRecord) {
    var state by remember(a.id) { mutableStateOf<RouteLookup?>(null) }
    LaunchedEffect(a.id) { state = if (a.isDemo) RouteLookup.None else app.routes.fromHealthConnect(a.id) }
    val ask = rememberLauncherForActivityResult(ExerciseRouteRequestContract()) { route ->
        state = route?.let { app.routes.fromRoute(a.id, it) }?.let { RouteLookup.Found(it).also { _ -> app.routeAdded() } } ?: RouteLookup.None
    }

    Eyebrow("Route")
    when (val s = state) {
        null -> Text("Looking for a GPS route…", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        is RouteLookup.Found -> {
            TerrainChoice(app)
            var terrain by remember(a.id) { mutableStateOf(app.terrain.cached(a.id)) }
            var looking by remember(a.id) { mutableStateOf(false) }
            LaunchedEffect(a.id, app.terrainEnabled) {
                if (app.terrainEnabled == true && terrain == null) {
                    looking = true; terrain = runCatching { app.terrain.climb(a.id, s.route) }.getOrNull(); looking = false
                    app.routeAdded()
                }
            }
            RouteDetails(s.route, a, terrain = terrain, terrainLooking = looking, terrainOn = app.terrainEnabled == true)
        }
        RouteLookup.NeedsConsent -> {
            Text("This workout has a GPS route from your phone. Android asks you to OK sharing it with this app, once per workout.",
                style = MaterialTheme.typography.bodyMedium)
            VoltButton("Show route on map", icon = Icons.Rounded.Map, onClick = { runCatching { ask.launch(a.id) } })
        }
        RouteLookup.None -> Text("No GPS route for this workout (it may have been indoors, or recorded without the phone's GPS).",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        is RouteLookup.Unavailable -> Text(s.why, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
}

@Composable
fun RouteDetails(route: RouteInfo, a: ActivityRecord?, colors: List<String>? = null,
                 terrain: com.fitcompanion.core.ElevationResult? = null, terrainLooking: Boolean = false, terrainOn: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RouteMap(route.latLngs, Modifier.clip(RoundedCornerShape(18.dp)), colors = colors)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column { Eyebrow("GPS distance"); Text("%.2f km".format(route.distanceMeters / 1000), style = MaterialTheme.typography.titleLarge) }
            Column {
                when {
                    terrain?.ascentMeters != null -> {
                        Eyebrow("Climb (terrain map)")
                        Text("%.0f m".format(terrain.ascentMeters), style = MaterialTheme.typography.titleLarge, color = Palette.Volt)
                    }
                    terrainLooking -> { Eyebrow("Climb"); Text("checking terrain…", style = MaterialTheme.typography.titleMedium, color = Palette.TextDim) }
                    terrainOn -> { Eyebrow("Climb"); Text("not available", style = MaterialTheme.typography.titleMedium, color = Palette.TextDim) }
                    else -> {
                        Eyebrow("Climb (rough, phone GPS)")
                        Text(route.elevation.ascentMeters?.let { "about %.0f m".format(it) } ?: "not available",
                            style = MaterialTheme.typography.titleLarge, color = Palette.TextDim)
                    }
                }
            }
        }
        a?.distanceMeters?.let { fit ->
            val diff = (route.distanceMeters - fit) / fit * 100
            if (kotlin.math.abs(diff) >= 5) Text("Your Fitbit recorded %.2f km; GPS measured %+.0f%% different. Both are shown as recorded.".format(fit / 1000, diff),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        if (route.splits.isNotEmpty()) {
            Eyebrow("Splits")
            route.splits.forEach { sp ->
                Row {
                    Text(if (sp.partial) "Last %.2f km".format(sp.distanceMeters / 1000) else "Km ${sp.index}",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
                    Text(Splits.formatPace(sp.paceSecondsPerKm) + " /km", style = MaterialTheme.typography.bodyMedium)
                    sp.averageHeartRateBpm?.let { Text("  · $it bpm", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
                }
            }
        }
        if (terrain?.ascentMeters != null) TerrainProfile(terrain)
        Text(route.source + ". " + when {
                terrain?.ascentMeters != null -> "Climb uses ground heights from a terrain map along your route (" + terrain.method.substringBefore(";").removePrefix("Terrain: ") +
                    "), not the phone's GPS altitude. Bridges over water are bridged; rises under 2 m aren't counted. "
                route.elevation.ascentMeters == null -> "This route's GPS altitude was too unreliable to estimate climb. "
                else -> "Phone GPS altitude is rough (often ±50 m); turn on the terrain map for accurate climb. "
            } + "Map © OpenStreetMap contributors · OpenFreeMap." +
            (if (terrain?.method?.contains("Queensland") == true) " Elevation © State of Queensland (CC BY)." else "") +
            (if (terrain?.method?.contains("Copernicus") == true) " Elevation: Copernicus DEM via Open-Meteo (CC BY 4.0)." else ""),
            style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
    }
}


/** One-time choice: accurate climb from terrain maps (sends route coordinates only). */
@Composable
fun TerrainChoice(app: AppController) {
    if (app.terrainEnabled != null) return
    com.fitcompanion.poc.ui.theme.SportCard(padding = 14.dp) {
        Eyebrow("Accurate climb", Palette.Volt)
        Text("Phone GPS altitude is too rough for climb. The app can look up the ground height along your routes from a terrain map " +
            "instead: the Queensland Government's LiDAR model (accurate to well under a metre in most of South East Queensland), " +
            "or a global 90 m model elsewhere.", style = MaterialTheme.typography.bodySmall)
        Text("This sends the route's map coordinates (no times, name, heart rate or other health data) to the Queensland Government " +
            "elevation service and, outside Queensland, Open-Meteo. You can turn it off in Setup.", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VoltButton("Use terrain map", onClick = { app.setTerrain(true) })
            com.fitcompanion.poc.ui.theme.GhostButton("No thanks", onClick = { app.setTerrain(false) })
        }
    }
}

/** Small elevation profile from the terrain model. */
@Composable
private fun TerrainProfile(e: com.fitcompanion.core.ElevationResult) {
    val prof = e.profile
    if (prof.size < 2) return
    val lo = prof.minOf { it.altitudeMeters }; val hi = prof.maxOf { it.altitudeMeters }.coerceAtLeast(lo + 10)
    Eyebrow("Elevation profile · %.0f–%.0f m".format(lo, prof.maxOf { it.altitudeMeters }))
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val maxD = prof.last().distanceMeters.coerceAtLeast(1.0)
        val path = androidx.compose.ui.graphics.Path()
        prof.forEachIndexed { i, p ->
            val x = (p.distanceMeters / maxD * size.width).toFloat()
            val y = (size.height - (p.altitudeMeters - lo) / (hi - lo) * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = androidx.compose.ui.graphics.Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Palette.Volt.copy(alpha = 0.15f))
        drawPath(path, Palette.Volt, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
    }
}
