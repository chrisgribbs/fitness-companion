package com.fitcompanion.poc.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Streetview
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fitcompanion.poc.ui.theme.Palette
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

/** OpenFreeMap vector tiles: free, no API key, OpenStreetMap data. */
const val STREET_STYLE = "https://tiles.openfreemap.org/styles/positron"

/**
 * Aerial view, no key needed. Queensland Government aerial photos (street-level detail, to zoom 20) inside
 * Queensland, over Sentinel-2 cloudless satellite imagery by EOX (10 m, free for non-commercial use) everywhere
 * else. Only map tiles are downloaded; nothing about you or your route is sent.
 */
private const val SATELLITE_JSON = """{"version":8,
 "sources":{
  "s2":{"type":"raster","tileSize":256,"maxzoom":15,
   "tiles":["https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg"],
   "attribution":"Sentinel-2 cloudless 2020 by EOX IT Services GmbH (contains modified Copernicus Sentinel data 2020)"},
  "qld":{"type":"raster","tileSize":256,"minzoom":6,"maxzoom":20,"bounds":[137.99,-29.18,153.56,-9.0],
   "tiles":["https://spatial-img.information.qld.gov.au/arcgis/rest/services/Basemaps/LatestStateProgram_AllUsers/ImageServer/tile/{z}/{y}/{x}"],
   "attribution":"Aerial imagery © State of Queensland"}},
 "layers":[
  {"id":"bg","type":"background","paint":{"background-color":"#0B0E13"}},
  {"id":"s2","type":"raster","source":"s2"},
  {"id":"qld","type":"raster","source":"qld"}]}"""

enum class MapLook { STREET, SATELLITE }

/** Map or satellite, remembered on this phone and shared by every map in the app. */
object MapLookPref {
    var look by mutableStateOf(MapLook.STREET)
        private set
    private var loaded = false
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        if (ctx.getSharedPreferences("maps", Context.MODE_PRIVATE).getBoolean("satellite", false)) look = MapLook.SATELLITE
    }
    fun toggle(ctx: Context) {
        look = if (look == MapLook.STREET) MapLook.SATELLITE else MapLook.STREET
        ctx.getSharedPreferences("maps", Context.MODE_PRIVATE).edit().putBoolean("satellite", look == MapLook.SATELLITE).apply()
    }
}

fun styleFor(look: MapLook): Style.Builder =
    if (look == MapLook.SATELLITE) Style.Builder().fromJson(SATELLITE_JSON) else Style.Builder().fromUri(STREET_STYLE)

/** One map's live state: the map, its loaded style, camera bookkeeping and the "recentre" action. */
class MapHandle {
    var map: MapLibreMap? = null
    var style: Style? = null
    var look: MapLook? = null
    var loading = false
    var fitted = -1
    /** Street level by default; if you pinch to zoom, your zoom is kept. */
    var followZoom = 17.0
    var userGesture = false
    var lastPoint: org.maplibre.android.geometry.LatLng? = null
    var recentre: () -> Unit = {}

    /**
     * Makes sure the map has the [look] style loaded. Returns true when it is ready to draw on now; otherwise it
     * loads it and calls [onStyle] once ready (route layers must be added again after every style change).
     */
    fun ensure(view: MapView, look: MapLook, interactive: Boolean, onFirst: (MapLibreMap) -> Unit = {},
               onStyle: (MapLibreMap, Style, MapLook) -> Unit): Boolean {
        if (map != null && style != null && this.look == look) return true
        if (loading) return false
        loading = true
        view.getMapAsync { m ->
            if (map == null) {
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                if (!interactive) m.uiSettings.setAllGesturesEnabled(false)
                map = m
                onFirst(m)
            }
            style = null
            m.setStyle(styleFor(look)) { s ->
                style = s; this.look = look; loading = false
                onStyle(m, s, look)
                // Switched again while this one was loading: catch up.
                if (MapLookPref.look != look) ensure(view, MapLookPref.look, interactive, onFirst, onStyle)
            }
        }
        return false
    }
}

fun Style.putSource(id: String, data: FeatureCollection) {
    getSourceAs<GeoJsonSource>(id)?.setGeoJson(data) ?: addSource(GeoJsonSource(id, data))
}

fun Style.putLayer(layer: Layer) { if (getLayer(layer.id) == null) addLayer(layer) }

/** A MapLibre view tied to the screen's lifecycle. */
@Composable
fun rememberMapView(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapLookPref.load(context)
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
        onDispose { lifecycle.removeObserver(observer); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    return mapView
}

/**
 * Opens Google Street View at a point, in the Google Maps app (or the browser if it isn't installed).
 * Only that one point goes to Google, and only when you tap the button.
 */
fun openStreetView(ctx: Context, lat: Double, lng: Double) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("google.streetview:cbll=$lat,$lng"))
        .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(app) } catch (e: ActivityNotFoundException) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$lat,$lng")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
fun MapPill(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Palette.Bg.copy(alpha = 0.85f)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.Volt, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.Text)
    }
}

/** Map/Satellite, back to the route, and Street View at the centre of the map. */
@Composable
fun MapControls(handle: MapHandle, modifier: Modifier = Modifier, recentreLabel: String = "Whole route", streetView: Boolean = true) {
    val ctx = LocalContext.current
    Column(modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        if (MapLookPref.look == MapLook.STREET) MapPill("Satellite", Icons.Rounded.Layers) { MapLookPref.toggle(ctx) }
        else MapPill("Map", Icons.Rounded.Map) { MapLookPref.toggle(ctx) }
        MapPill(recentreLabel, Icons.Rounded.CenterFocusStrong) { handle.recentre() }
        if (streetView) MapPill("Street View", Icons.Rounded.Streetview) {
            handle.map?.cameraPosition?.target?.let { openStreetView(ctx, it.latitude, it.longitude) }
        }
    }
}

/**
 * A map inside a scrolling page. Swipes scroll the page (so the map can't trap you); tap it to open a
 * full-screen map you can drag and zoom, with satellite view, "whole route" and Street View.
 */
@Composable
fun MapPreview(height: Dp, modifier: Modifier = Modifier, map: @Composable (Modifier, MapHandle, Boolean) -> Unit) {
    var full by remember { mutableStateOf(false) }
    val handle = remember { MapHandle() }
    Box(modifier.fillMaxWidth().height(height)) {
        map(Modifier.fillMaxSize(), handle, false)
        Box(Modifier.matchParentSize().clickable { full = true })
        Box(Modifier.align(Alignment.BottomEnd).padding(10.dp)) { MapPill("Explore map", Icons.Rounded.OpenInFull) { full = true } }
    }
    if (full) {
        val fullHandle = remember { MapHandle() }
        Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Palette.Bg)) {
                map(Modifier.fillMaxSize(), fullHandle, true)
                // Street View opens wherever this cross is.
                Box(Modifier.align(Alignment.Center).size(18.dp).border(2.dp, Palette.Volt, CircleShape))
                Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp)) { MapPill("Close", Icons.Rounded.Close) { full = false } }
                MapControls(fullHandle, Modifier.align(Alignment.TopEnd).statusBarsPadding())
                Text("Drag and pinch to explore. Street View opens Google Maps at the circle.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Text,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 34.dp)
                        .clip(RoundedCornerShape(50)).background(Palette.Bg.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
