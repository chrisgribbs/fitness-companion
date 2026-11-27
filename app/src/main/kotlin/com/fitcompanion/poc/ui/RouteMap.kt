package com.fitcompanion.poc.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_STYLE = "https://tiles.openfreemap.org/styles/positron"

private class MapHolder {
    var map: MapLibreMap? = null; var style: Style? = null; var fittedFor = -1; var loading = false
    /** Street level by default; if you pinch to zoom, your zoom is kept. */
    var followZoom = 17.0; var userGesture = false
}

/**
 * Draws a GPS route on an OpenStreetMap map. [colors] (one hex per segment, optional) colours the line, e.g. by
 * heart-rate zone. With [follow] the camera tracks the newest point (live workouts); otherwise it fits the whole route.
 * Only map tiles are downloaded; your route is drawn on the phone and never sent anywhere.
 */
@Composable
fun RouteMap(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier, colors: List<String>? = null,
             follow: Boolean = false, height: Dp = 280.dp) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { MapHolder() }
    val mapView = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(null) } }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxWidth().height(height), update = { view ->
        val style = holder.style
        val map = holder.map
        if (style != null && map != null) draw(map, style, points, colors, follow, holder)
        else if (!holder.loading) {
            holder.loading = true
            view.getMapAsync { m ->
                m.uiSettings.isRotateGesturesEnabled = false
                m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) holder.userGesture = true }
                m.addOnCameraIdleListener { if (holder.userGesture) { holder.followZoom = m.cameraPosition.zoom; holder.userGesture = false } }
                m.uiSettings.isAttributionEnabled = true
                m.setStyle(Style.Builder().fromUri(ROUTE_STYLE)) { s ->
                    s.addSource(GeoJsonSource("trk"))
                    s.addLayer(LineLayer("trk-casing", "trk").withProperties(
                        PropertyFactory.lineColor("#0B0E13"), PropertyFactory.lineWidth(8f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                    s.addLayer(LineLayer("trk", "trk").withProperties(
                        PropertyFactory.lineColor(Expression.get("c")), PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                    s.addSource(GeoJsonSource("ends"))
                    s.addLayer(CircleLayer("ends", "ends").withProperties(
                        PropertyFactory.circleRadius(Expression.get("r")), PropertyFactory.circleColor(Expression.get("c")),
                        PropertyFactory.circleStrokeColor("#0B0E13"), PropertyFactory.circleStrokeWidth(2.5f)))
                    holder.map = m; holder.style = s
                    draw(m, s, points, colors, follow, holder)
                }
            }
        }
    })
}

private fun draw(map: MapLibreMap, style: Style, points: List<Pair<Double, Double>>, colors: List<String>?, follow: Boolean, h: MapHolder) {
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
    style.getSourceAs<GeoJsonSource>("trk")?.setGeoJson(FeatureCollection.fromFeatures(features))
    val ends = listOf(
        Feature.fromGeometry(pt(points.first())).apply { addStringProperty("c", "#4ADE80"); addNumberProperty("r", 6f) },
        Feature.fromGeometry(pt(points.last())).apply { addStringProperty("c", "#D4FF3A"); addNumberProperty("r", 8f) },
    )
    style.getSourceAs<GeoJsonSource>("ends")?.setGeoJson(FeatureCollection.fromFeatures(ends))

    if (follow) {
        // Always aim for a fixed zoom (street level, or yours if you pinched). Reading the camera's current zoom
        // mid-animation is what left the map stuck on the whole world.
        val last = LatLng(points.last().first, points.last().second)
        if (h.fittedFor < 0) map.moveCamera(CameraUpdateFactory.newLatLngZoom(last, h.followZoom))
        else if (!h.userGesture) map.animateCamera(CameraUpdateFactory.newLatLngZoom(last, h.followZoom), 500)
        h.fittedFor = points.size
    } else if (h.fittedFor != points.size) {
        h.fittedFor = points.size
        if (points.size == 1) map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].first, points[0].second), 15.0))
        else map.moveCamera(CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.first, it.second)) } }.build(), 60))
    }
}
