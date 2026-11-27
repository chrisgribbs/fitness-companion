package com.fitcompanion.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.remember
import com.fitcompanion.core.Milestone
import com.fitcompanion.poc.AppController
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.fitcompanion.poc.ui.theme.Palette
import org.maplibre.android.style.layers.Property
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fitcompanion.core.JourneyRoute
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The whole virtual route (dashed), the part you've covered (teal), landmarks, and a dot where you are.
 * In the page it's a preview; tap it for a full-screen map with satellite view and Street View.
 * Only map tiles are downloaded; your progress is drawn on the phone and never sent anywhere.
 */
@Composable
fun JourneyMap(route: JourneyRoute, travelledMeters: Double, modifier: Modifier = Modifier,
               milestones: List<Milestone>? = null, app: AppController? = null) {
    val total = route.totalMeters
    val covered = travelledMeters.coerceIn(0.0, total)
    // Stages: the start, then each landmark along the way (named route stops, or the walk's milestones).
    val stages = remember(route, milestones) {
        val first = route.waypoints.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: "Start"
        listOf(Milestone("$first (start)", 0.0)) + (milestones ?: route.waypointMilestones()).filter { it.atMeters > 0 }.sortedBy { it.atMeters }
    }
    MapPreview(280.dp, modifier, legend = { MapLegend(covered, total - covered) }) { m, handle, interactive ->
        Box(m) {
            JourneyMapView(route, travelledMeters, stages, handle, interactive, Modifier.fillMaxSize())
            val pick = handle.picked
            if (interactive && app != null && pick != null) StageCard(app, pick, covered, Modifier.align(Alignment.BottomCenter)) { handle.picked = null }
        }
    }
}

/** What you see when you tap a stage (or yourself) on the full-screen map: where it is and a photo of the spot. */
@Composable
private fun StageCard(app: AppController, s: MapStage, covered: Double, modifier: Modifier, onClose: () -> Unit) {
    val reached = s.atMeters <= covered + 1
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp).clip(RoundedCornerShape(22.dp)).background(Palette.Surface)
        .heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name.uppercase(), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                Text(when {
                    s.isYou -> "You're here · ${km(covered)} along the route"
                    s.atMeters == 0.0 -> "Where the journey starts"
                    reached -> "✓ Reached · ${km(s.atMeters)} along the route"
                    else -> "${km(s.atMeters - covered)} ahead · at ${km(s.atMeters)}"
                }, style = MaterialTheme.typography.bodySmall, color = if (reached || s.isYou) Palette.Volt else Palette.TextDim)
            }
            MapPill("Close", Icons.Rounded.Close, onClose)
        }
        // Keyed by the spot so tapping another stage loads its own photo.
        androidx.compose.runtime.key(s.lat, s.lon) { TrailPhotoPanel(app, s.lat, s.lon, s.name) }
    }
}

private const val COVERED = "#D4FF3A"

private fun km(m: Double) = if (m >= 10_000) "%,.0f km".format(m / 1000) else "%.1f km".format(m / 1000)

/** Key: solid bright line = covered, dashed = still to go, white dot = you. */
@Composable
private fun MapLegend(covered: Double, left: Double) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Palette.Bg.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(16.dp).height(5.dp).clip(RoundedCornerShape(50)).background(Palette.Volt))
        Text("Covered ${km(covered)}", style = MaterialTheme.typography.labelSmall, color = Palette.Text)
        Spacer(Modifier.width(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { repeat(3) { Box(Modifier.width(4.dp).height(3.dp).background(Palette.TextDim)) } }
        Text("To go ${km(left)}", style = MaterialTheme.typography.labelSmall, color = Palette.Text)
    }
}

@Composable
private fun JourneyMapView(route: JourneyRoute, travelledMeters: Double, stages: List<Milestone>,
                           h: MapHandle, interactive: Boolean, modifier: Modifier) {
    val view = rememberMapView()
    AndroidView(factory = { view }, modifier = modifier, update = { v ->
        val look = MapLookPref.look
        val ready = h.ensure(v, look, interactive, onFirst = { m -> if (interactive) listenForStageTaps(m, h) }) { m, s, l ->
            drawJourney(m, s, l, route, travelledMeters, stages, h) }
        if (ready) drawJourney(h.map!!, h.style!!, look, route, travelledMeters, stages, h)
    })
}

/** A tap near a stage dot or your own dot opens that stage's card. */
private fun listenForStageTaps(map: MapLibreMap, h: MapHandle) {
    map.addOnMapClickListener { latLng ->
        val pt = map.projection.toScreenLocation(latLng)
        val r = 28f * android.content.res.Resources.getSystem().displayMetrics.density // ~28 dp around your finger
        val box = android.graphics.RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
        val f = map.queryRenderedFeatures(box, "me", "waypoints").minByOrNull { feat ->
            (feat.geometry() as? Point)?.let { g ->
                val sp = map.projection.toScreenLocation(LatLng(g.latitude(), g.longitude()))
                (sp.x - pt.x) * (sp.x - pt.x) + (sp.y - pt.y) * (sp.y - pt.y)
            } ?: Float.MAX_VALUE
        }
        val g = f?.geometry() as? Point
        if (f != null && g != null) {
            h.picked = MapStage(f.getStringProperty("name") ?: "Here", g.latitude(), g.longitude(),
                f.getNumberProperty("at")?.toDouble() ?: 0.0, f.hasProperty("you"))
            true
        } else false
    }
}

private fun drawJourney(map: MapLibreMap, style: Style, look: MapLook, route: JourneyRoute, travelledMeters: Double,
                        stages: List<Milestone>, h: MapHandle) {
    fun line(points: List<Pair<Double, Double>>) =
        FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) })))

    val all = route.waypoints.map { it.latitude to it.longitude }
    if (all.isEmpty()) return
    val done = route.travelledPath(travelledMeters)
    val here = route.positionAt(travelledMeters)
    val sat = look == MapLook.SATELLITE

    // Still to go: thin dashed line (white on satellite so it shows up). Covered: thick bright line on a dark edge.
    style.putSource("route-all", line(all))
    style.putLayer(LineLayer("route-all", "route-all").withProperties(
        PropertyFactory.lineColor(if (sat) "#FFFFFF" else "#5A6470"), PropertyFactory.lineWidth(3.5f),
        PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f))))

    style.putSource("route-done", if (done.size >= 2) line(done) else FeatureCollection.fromFeatures(emptyList()))
    style.putLayer(LineLayer("route-done-edge", "route-done").withProperties(
        PropertyFactory.lineColor("#0B0E13"), PropertyFactory.lineWidth(9f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    style.putLayer(LineLayer("route-done", "route-done").withProperties(
        PropertyFactory.lineColor(COVERED), PropertyFactory.lineWidth(6f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))

    // Landmarks only: a preset's path has hundreds of unnamed points that shouldn't get dots.
    // Each stage dot carries its name and distance, so tapping it can show the right card.
    style.putSource("waypoints", FeatureCollection.fromFeatures(stages.map { st ->
        val (la, lo) = route.pointAt(st.atMeters)
        Feature.fromGeometry(Point.fromLngLat(lo, la)).apply { addStringProperty("name", st.name); addNumberProperty("at", st.atMeters) }
    }))
    style.putLayer(CircleLayer("waypoints", "waypoints").withProperties(
        PropertyFactory.circleRadius(5f), PropertyFactory.circleColor("#FFFFFF"),
        PropertyFactory.circleStrokeColor("#5A6470"), PropertyFactory.circleStrokeWidth(1.5f)))

    style.putSource("me", FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(here.longitude, here.latitude)).apply {
        addStringProperty("name", "You are here"); addNumberProperty("at", travelledMeters); addBooleanProperty("you", true) }))
    style.putLayer(CircleLayer("me", "me").withProperties(
        PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#FFFFFF"),
        PropertyFactory.circleStrokeColor(COVERED), PropertyFactory.circleStrokeWidth(4f)))

    val bounds = if (all.size >= 2) LatLngBounds.Builder().apply { all.forEach { include(LatLng(it.first, it.second)) } }.build() else null
    h.recentre = {
        if (bounds != null) map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60), 600)
        else map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(all[0].first, all[0].second), 14.0), 600)
    }
    // Fit the route once; after that the camera is yours (or the preview's) until you tap "Whole route".
    if (h.fitted < 0) {
        h.fitted = 1
        runCatching {
            if (bounds != null) map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60))
            else map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(all[0].first, all[0].second), 14.0))
        }
    }
}
