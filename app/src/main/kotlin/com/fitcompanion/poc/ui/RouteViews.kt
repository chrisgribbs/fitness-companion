package com.fitcompanion.poc.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
        is RouteLookup.Found -> RouteDetails(s.route, a)
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
fun RouteDetails(route: RouteInfo, a: ActivityRecord?, colors: List<String>? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RouteMap(route.latLngs, Modifier.clip(RoundedCornerShape(18.dp)), colors = colors)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column { Eyebrow("GPS distance"); Text("%.2f km".format(route.distanceMeters / 1000), style = MaterialTheme.typography.titleLarge) }
            route.elevation.ascentMeters?.let { up ->
                Column { Eyebrow("Climb (estimate)"); Text("%.0f m".format(up), style = MaterialTheme.typography.titleLarge, color = Palette.Volt) }
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
        Text(route.source + ". Climb is smoothed from GPS altitude, so it's an estimate. Map © OpenStreetMap contributors · OpenFreeMap.",
            style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
    }
}
