package com.fitcompanion.poc.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fitcompanion.core.JourneyRoute
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** OpenFreeMap vector tiles: free, no API key, OpenStreetMap data (attribution shown by MapLibre). */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/**
 * The whole virtual route (grey), the part you've covered (blue), waypoints, and a dot where you are.
 * Only map tiles are downloaded; your progress is drawn on the phone and never sent anywhere.
 */
@Composable
fun JourneyMap(route: JourneyRoute, travelledMeters: Double, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

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
        onDispose { lifecycle.removeObserver(observer); mapView.onDestroy() }
    }

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxWidth().height(280.dp), update = { view ->
        view.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                fun line(points: List<Pair<Double, Double>>) =
                    LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) })

                val all = route.waypoints.map { it.latitude to it.longitude }
                val done = route.travelledPath(travelledMeters)
                val here = route.positionAt(travelledMeters)

                style.addSource(GeoJsonSource("route-all", Feature.fromGeometry(line(all))))
                style.addLayer(LineLayer("route-all", "route-all").withProperties(
                    PropertyFactory.lineColor("#8D9AA6"), PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineDasharray(arrayOf(2f, 2f))))

                if (done.size >= 2) {
                    style.addSource(GeoJsonSource("route-done", Feature.fromGeometry(line(done))))
                    style.addLayer(LineLayer("route-done", "route-done").withProperties(
                        PropertyFactory.lineColor("#1E6FD9"), PropertyFactory.lineWidth(5f)))
                }

                style.addSource(GeoJsonSource("waypoints", FeatureCollection.fromFeatures(
                    route.waypoints.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })))
                style.addLayer(CircleLayer("waypoints", "waypoints").withProperties(
                    PropertyFactory.circleRadius(4f), PropertyFactory.circleColor("#FFFFFF"),
                    PropertyFactory.circleStrokeColor("#5A6470"), PropertyFactory.circleStrokeWidth(1.5f)))

                style.addSource(GeoJsonSource("me", Feature.fromGeometry(Point.fromLngLat(here.longitude, here.latitude))))
                style.addLayer(CircleLayer("me", "me").withProperties(
                    PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#1E6FD9"),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f)))

                val bounds = LatLngBounds.Builder().apply { all.forEach { include(LatLng(it.first, it.second)) } }.build()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60))
            }
        }
    })
}
