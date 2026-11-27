package com.fitcompanion.poc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Draws a GPS route. [colors] (one hex per segment, optional) colours the line, e.g. by heart-rate zone.
 * With [follow] (live workouts) the map tracks your newest point and can be dragged and pinched right there, with a
 * "Back to me" button. Otherwise it's a preview in the page: tap for a full-screen map.
 * Only map tiles are downloaded; your route is drawn on the phone and never sent anywhere.
 */
@Composable
fun RouteMap(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier, colors: List<String>? = null,
             follow: Boolean = false, height: Dp = 280.dp) {
    if (follow) {
        val handle = remember { MapHandle() }
        Box(modifier.fillMaxWidth().height(height)) {
            RouteMapView(points, colors, true, handle, true, Modifier.fillMaxSize())
            MapControls(handle, Modifier.align(Alignment.TopEnd), recentreLabel = "Back to me", streetView = false)
        }
    } else {
        MapPreview(height, modifier) { m, handle, interactive -> RouteMapView(points, colors, false, handle, interactive, m) }
    }
}

@Composable
private fun RouteMapView(points: List<Pair<Double, Double>>, colors: List<String>?, follow: Boolean, h: MapHandle,
                         interactive: Boolean, modifier: Modifier) {
    val view = rememberMapView()
    AndroidView(factory = { view }, modifier = modifier, update = { v ->
        val look = MapLookPref.look
        val ready = h.ensure(v, look, interactive, onFirst = { m ->
            m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) h.userGesture = true }
            m.addOnCameraIdleListener {
                if (h.userGesture && follow) {
                    h.followZoom = m.cameraPosition.zoom
                    // Just zoomed (you're still in view): keep following. Dragged away: stay put until "Back to me".
                    val me = h.lastPoint
                    if (me != null && m.projection.visibleRegion.latLngBounds.contains(me)) h.userGesture = false
                }
            }
        }) { m, s, _ -> addRouteLayers(s); draw(m, s, points, colors, follow, h) }
        if (ready) draw(h.map!!, h.style!!, points, colors, follow, h)
    })
}

private fun addRouteLayers(s: Style) {
    s.putSource("trk", FeatureCollection.fromFeatures(emptyList()))
    s.putLayer(LineLayer("trk-casing", "trk").withProperties(
        PropertyFactory.lineColor("#0B0E13"), PropertyFactory.lineWidth(8f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    s.putLayer(LineLayer("trk", "trk").withProperties(
        PropertyFactory.lineColor(Expression.get("c")), PropertyFactory.lineWidth(5f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    s.putSource("ends", FeatureCollection.fromFeatures(emptyList()))
    s.putLayer(CircleLayer("ends", "ends").withProperties(
        PropertyFactory.circleRadius(Expression.get("r")), PropertyFactory.circleColor(Expression.get("c")),
        PropertyFactory.circleStrokeColor("#0B0E13"), PropertyFactory.circleStrokeWidth(2.5f)))
}

private fun draw(map: MapLibreMap, style: Style, points: List<Pair<Double, Double>>, colors: List<String>?, follow: Boolean, h: MapHandle) {
    if (points.isEmpty()) return
    fun pt(p: Pair<Double, Double>) = Point.fromLngLat(p.second, p.first)
    // Group consecutive segments of the same colour into one line each.
    val features = mutableListOf<Feature>()
    if (points.size >= 2) {
        var start = 0
        for (i in 1 until points.size) {
            val c = colors?.getOrNull(i - 1) ?: "#35D6C4"
            val next = colors?.getOrNull(i) ?: "#35D6C4"
            if (i == points.size - 1 || next != c) {
                features += Feature.fromGeometry(LineString.fromLngLats(points.subList(start, i + 1).map(::pt))).apply { addStringProperty("c", c) }
                start = i
            }
        }
    }
    style.putSource("trk", FeatureCollection.fromFeatures(features))
    style.putSource("ends", FeatureCollection.fromFeatures(listOf(
        Feature.fromGeometry(pt(points.first())).apply { addStringProperty("c", "#4ADE80"); addNumberProperty("r", 6f) },
        Feature.fromGeometry(pt(points.last())).apply { addStringProperty("c", "#D4FF3A"); addNumberProperty("r", 8f) },
    )))

    val last = LatLng(points.last().first, points.last().second)
    h.lastPoint = last
    val bounds = if (points.size >= 2) LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.first, it.second)) } }.build() else null
    fun fitAll(animate: Boolean) {
        val u = if (bounds != null) CameraUpdateFactory.newLatLngBounds(bounds, 60) else CameraUpdateFactory.newLatLngZoom(last, 15.0)
        runCatching { if (animate) map.animateCamera(u, 600) else map.moveCamera(u) }
    }

    if (follow) {
        h.recentre = { h.userGesture = false; map.animateCamera(CameraUpdateFactory.newLatLngZoom(last, h.followZoom), 500) }
        // Aim for a fixed zoom (street level, or yours if you pinched). If you've dragged the map away, it stays
        // there until you tap "Back to me".
        if (h.fitted < 0) map.moveCamera(CameraUpdateFactory.newLatLngZoom(last, h.followZoom))
        else if (!h.userGesture) map.animateCamera(CameraUpdateFactory.newLatLngZoom(last, h.followZoom), 500)
        h.fitted = points.size
    } else {
        h.recentre = { fitAll(true) }
        if (h.fitted != points.size) { h.fitted = points.size; fitAll(false) }
    }
}
