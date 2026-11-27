package com.fitcompanion.poc.ui

import androidx.compose.runtime.Composable
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
fun JourneyMap(route: JourneyRoute, travelledMeters: Double, modifier: Modifier = Modifier, milestoneMeters: List<Double>? = null) {
    MapPreview(280.dp, modifier) { m, handle, interactive ->
        JourneyMapView(route, travelledMeters, milestoneMeters, handle, interactive, m)
    }
}

@Composable
private fun JourneyMapView(route: JourneyRoute, travelledMeters: Double, milestoneMeters: List<Double>?,
                           h: MapHandle, interactive: Boolean, modifier: Modifier) {
    val view = rememberMapView()
    AndroidView(factory = { view }, modifier = modifier, update = { v ->
        val look = MapLookPref.look
        val ready = h.ensure(v, look, interactive) { m, s, l -> drawJourney(m, s, l, route, travelledMeters, milestoneMeters, h) }
        if (ready) drawJourney(h.map!!, h.style!!, look, route, travelledMeters, milestoneMeters, h)
    })
}

private fun drawJourney(map: MapLibreMap, style: Style, look: MapLook, route: JourneyRoute, travelledMeters: Double,
                        milestoneMeters: List<Double>?, h: MapHandle) {
    fun line(points: List<Pair<Double, Double>>) =
        FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) })))

    val all = route.waypoints.map { it.latitude to it.longitude }
    if (all.isEmpty()) return
    val done = route.travelledPath(travelledMeters)
    val here = route.positionAt(travelledMeters)
    val sat = look == MapLook.SATELLITE

    style.putSource("route-all", line(all))
    style.putLayer(LineLayer("route-all", "route-all").withProperties(
        PropertyFactory.lineColor(if (sat) "#FFFFFF" else "#5A6470"), PropertyFactory.lineWidth(3f),
        PropertyFactory.lineDasharray(arrayOf(2f, 2f))))

    style.putSource("route-done", if (done.size >= 2) line(done) else FeatureCollection.fromFeatures(emptyList()))
    style.putLayer(LineLayer("route-done", "route-done").withProperties(
        PropertyFactory.lineColor("#0FB5A2"), PropertyFactory.lineWidth(6f)))

    // Landmarks only: a preset's path has hundreds of unnamed points that shouldn't get dots.
    val marks = milestoneMeters?.map { route.pointAt(it) }
        ?: route.waypoints.filter { it.name.isNotBlank() }.map { it.latitude to it.longitude }
    style.putSource("waypoints", FeatureCollection.fromFeatures(marks.map { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) }))
    style.putLayer(CircleLayer("waypoints", "waypoints").withProperties(
        PropertyFactory.circleRadius(4f), PropertyFactory.circleColor("#FFFFFF"),
        PropertyFactory.circleStrokeColor("#5A6470"), PropertyFactory.circleStrokeWidth(1.5f)))

    style.putSource("me", FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(here.longitude, here.latitude))))
    style.putLayer(CircleLayer("me", "me").withProperties(
        PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#D4FF3A"),
        PropertyFactory.circleStrokeColor("#0B0E13"), PropertyFactory.circleStrokeWidth(3f)))

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
