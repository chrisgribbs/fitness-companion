package com.fitcompanion.poc.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fitcompanion.core.HeartRateParser
import com.fitcompanion.core.HeartZones
import com.fitcompanion.core.HrZone
import com.fitcompanion.core.LiveSessionSummary
import com.fitcompanion.core.LiveStats
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Live heart rate from a Fitbit Air (or any band/strap) over the standard Bluetooth Heart Rate Service (0x180D),
 * which Google documents for Fitbit's "Share heart rate" feature. Only heart rate and beat-to-beat intervals are read;
 * nothing is sent to the band and nothing leaves the phone. One shared instance for the whole app.
 */
@SuppressLint("MissingPermission")   // callers check Bluetooth permissions first
object LiveHeart {
    val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    enum class Status { IDLE, SCANNING, CONNECTING, CONNECTED, RECONNECTING, ERROR }
    data class Found(val name: String, val address: String)

    var status by mutableStateOf(Status.IDLE); private set
    var statusText by mutableStateOf("Not connected"); private set
    var found by mutableStateOf<List<Found>>(emptyList()); private set
    var deviceName by mutableStateOf<String?>(null); private set
    var bpm by mutableStateOf<Int?>(null); private set
    var contact by mutableStateOf<Boolean?>(null); private set
    var lastReadingAt by mutableStateOf(0L); private set

    // Session
    var recording by mutableStateOf(false); private set
    var sessionStart by mutableStateOf(0L); private set
    var samples by mutableStateOf<List<Pair<Long, Int>>>(emptyList()); private set
    private val rr = mutableListOf<Double>()
    var zones by mutableStateOf<List<HrZone>>(emptyList())
    var targetZone by mutableStateOf<Int?>(null)
    var alertsOn by mutableStateOf(true)
    var alertText by mutableStateOf<String?>(null); private set
    private var outOfZoneSince = 0L
    private var lastAlertAt = 0L

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var wantConnected = false
    private var reconnectTries = 0

    fun init(context: Context) { if (!::appContext.isInitialized) appContext = context.applicationContext }
    private val prefs get() = appContext.getSharedPreferences("live_heart", Context.MODE_PRIVATE)
    val savedAddress: String? get() = prefs.getString("address", null)
    val savedName: String? get() = prefs.getString("name", null)

    private fun adapter() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    fun bluetoothOn() = runCatching { adapter()?.isEnabled == true }.getOrDefault(false)

    /**
     * Choosing from a list of any nearby band/strap. Kept for the future (other bands); for now the app only
     * connects to a Fitbit Air, automatically.
     */
    const val SHOW_DEVICE_PICKER = false

    /**
     * Live heart rate from the band. Switched off for now: live workouts are GPS map, distance and pace only.
     * All the heart-rate code stays in place; set this to true to bring it back.
     */
    const val HEART_RATE_ENABLED = false
    private var autoConnect = false
    private fun looksLikeFitbit(name: String) = name.contains("fitbit", true) || name.contains("air", true) || name.contains("google", true)

    /** Finds the Fitbit Air and connects to it without showing a list. */
    fun connectFitbitAir() { autoConnect = true; scan() }

    // ---- Scanning ----------------------------------------------------------------------------------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = result.scanRecord?.deviceName ?: runCatching { d.name }.getOrNull() ?: "Heart rate sensor"
            if (found.none { it.address == d.address }) found = found + Found(name, d.address)
            if (autoConnect && looksLikeFitbit(name)) { autoConnect = false; connect(d.address, name) }
        }
        override fun onScanFailed(errorCode: Int) { status = Status.ERROR; statusText = "Bluetooth scan failed ($errorCode). Try again." }
    }

    fun scan() {
        val a = adapter() ?: run { status = Status.ERROR; statusText = "This phone has no Bluetooth."; return }
        if (!a.isEnabled) { status = Status.ERROR; statusText = "Turn Bluetooth on, then try again."; return }
        found = emptyList(); status = Status.SCANNING; statusText = "Looking for your Fitbit Air…"
        val scanner = a.bluetoothLeScanner ?: return
        runCatching {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        }.onFailure { status = Status.ERROR; statusText = "Couldn't scan: ${it.message}" }
        main.postDelayed({ stopScan() }, 12_000)
    }

    fun stopScan() {
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(scanCb) }
        // Auto-connect: if only one heart-rate device answered, that's the band.
        if (autoConnect && status == Status.SCANNING) {
            autoConnect = false
            found.singleOrNull()?.let { connect(it.address, it.name); return }
        }
        if (status == Status.SCANNING) {
            status = Status.IDLE
            statusText = if (found.isEmpty() || !SHOW_DEVICE_PICKER) "Couldn't find your Fitbit Air. In Google Health, turn on Share heart rate (Always visible), keep the band close, and try again."
                         else "Tap your band to connect."
        }
    }

    // ---- Connecting ----------------------------------------------------------------------------------
    fun connect(address: String, name: String) {
        stopScan()
        val device: BluetoothDevice = runCatching { adapter()!!.getRemoteDevice(address) }.getOrNull()
            ?: run { status = Status.ERROR; statusText = "Couldn't find that device."; return }
        prefs.edit().putString("address", address).putString("name", name).apply()
        deviceName = name; wantConnected = true; reconnectTries = 0
        status = Status.CONNECTING; statusText = "Connecting to $name…"
        gatt?.close()
        gatt = device.connectGatt(appContext, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        wantConnected = false
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; bpm = null; status = Status.IDLE; statusText = "Disconnected"
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) = main.post {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status = Status.CONNECTING; statusText = "Connected, starting heart rate…"
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bpm = null
                if (wantConnected && reconnectTries < 20) {
                    reconnectTries++
                    status = Status.RECONNECTING; statusText = "Lost the band, reconnecting…"
                    main.postDelayed({ if (wantConnected) runCatching { g.connect() } }, 2000)
                } else { status = Status.IDLE; statusText = "Disconnected" }
            }
        }.let { }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (ch == null) { main.post { status = Status.ERROR; statusText = "That device doesn't share heart rate." }; return }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else enableLegacy(g, d)
            main.post { status = Status.CONNECTED; statusText = "Live from ${deviceName ?: "your band"}"; reconnectTries = 0 }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = onValue(value)

        @Deprecated("Android 12 and lower")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") onValue(c.value ?: return)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableLegacy(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    private fun onValue(value: ByteArray) {
        val r = HeartRateParser.parse(value) ?: return
        main.post {
            if (r.bpm <= 0) return@post
            val now = System.currentTimeMillis()
            bpm = r.bpm; contact = r.contact; lastReadingAt = now
            if (status != Status.CONNECTED) { status = Status.CONNECTED; statusText = "Live from ${deviceName ?: "your band"}" }
            if (recording) {
                samples = (samples + (now to r.bpm)).takeLast(4 * 3600)
                rr += r.rrMs
                checkAlert(now, r.bpm)
            }
        }
    }

    // ---- GPS (the phone's own) ------------------------------------------------------------------------
    var gpsOn by mutableStateOf(true)   // always on while heart rate is disabled
    var gpsPoints by mutableStateOf<List<com.fitcompanion.core.TrackPoint>>(emptyList()); private set
    var gpsColors by mutableStateOf<List<String>>(emptyList()); private set
    var gpsDistance by mutableStateOf(0.0); private set
    var gpsStatus by mutableStateOf<String?>(null); private set
    private var gpsListening = false
    private val gpsListener = android.location.LocationListener { loc -> onLocation(loc) }

    private fun startGps() {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        gpsStatus = "Waiting for GPS… (best outdoors)"
        runCatching {
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            gpsListening = true
        }.onFailure { gpsStatus = "Couldn't start GPS: ${it.message}" }
    }

    private fun stopGps() {
        if (!gpsListening) return
        runCatching { (appContext.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager).removeUpdates(gpsListener) }
        gpsListening = false
    }

    private fun onLocation(loc: android.location.Location) {
        if (!recording) return
        val acc = if (loc.hasAccuracy()) loc.accuracy else 99f
        gpsStatus = "GPS ±${acc.toInt()} m"
        if (acc > 25f) { gpsStatus = "Weak GPS (±${acc.toInt()} m): keep going, it'll lock on"; return }
        val p = com.fitcompanion.core.TrackPoint(time = java.time.Instant.ofEpochMilli(loc.time), latitude = loc.latitude, longitude = loc.longitude,
            altitudeMeters = if (loc.hasAltitude()) loc.altitude else null, heartRateBpm = bpm)
        val last = gpsPoints.lastOrNull()
        if (last != null) {
            val d = com.fitcompanion.core.Geo.haversineMeters(last.latitude!!, last.longitude!!, p.latitude!!, p.longitude!!)
            val secs = (loc.time - last.time.toEpochMilli()) / 1000.0
            if (d < 3) return                                  // standing still: ignore GPS jitter
            if (secs > 0 && d / secs > 12) return               // faster than a sprint: a GPS glitch
            gpsDistance += d
            val z = bpm?.let { HeartZones.zoneOf(it, zones) } ?: 0
            gpsColors = gpsColors + zoneHex[z]
        }
        gpsPoints = gpsPoints + p
    }

    /** Pace over roughly the last minute, in seconds per km. */
    fun currentPace(): Double? {
        val pts = gpsPoints; if (pts.size < 3) return null
        val end = pts.last().time.toEpochMilli()
        val recent = pts.filter { end - it.time.toEpochMilli() <= 60_000 }
        if (recent.size < 2) return null
        val d = recent.zipWithNext { a, b -> com.fitcompanion.core.Geo.haversineMeters(a.latitude!!, a.longitude!!, b.latitude!!, b.longitude!!) }.sum()
        val t = (recent.last().time.toEpochMilli() - recent.first().time.toEpochMilli()) / 1000.0
        return if (d > 20 && t > 0) t / (d / 1000) else null
    }

    private val zoneHex = listOf("#8C97A8", "#6EA8FF", "#35D6C4", "#D4FF3A", "#FF6A3D", "#FF4D6D")
    private val routes by lazy { RouteStore(appContext) }
    fun route(id: String): RouteInfo? = routes.cached(id)

    // ---- Session + alerts ----------------------------------------------------------------------------
    fun startSession(withGps: Boolean) {
        samples = emptyList(); rr.clear(); sessionStart = System.currentTimeMillis(); recording = true
        outOfZoneSince = 0; alertText = null
        gpsPoints = emptyList(); gpsColors = emptyList(); gpsDistance = 0.0; gpsStatus = null
        gpsOn = withGps
        if (withGps) startGps()
    }

    /** Ends the session, saves its summary (and route) on the phone and returns it. */
    fun stopSession(): LiveSessionSummary? {
        recording = false; alertText = null
        stopGps()
        val now = System.currentTimeMillis()
        var s = LiveStats.summarise(samples, rr, zones, targetZone)
            ?: if (gpsPoints.size >= 2) LiveSessionSummary(sessionStart, ((now - sessionStart) / 1000).toInt(), 0, 0, 0, List(6) { 0 }, null, null, 0) else null
        if (s == null) return null
        if (gpsPoints.size >= 2) {
            val id = "live-$sessionStart"
            val route = RouteInfo(gpsPoints, "Recorded with your phone's GPS during a live workout")
            routes.save(id, route)
            s = s.copy(distanceMeters = gpsDistance, ascentMeters = route.elevation.ascentMeters, routeId = id)
        }
        if (s.durationSec >= 60) saveSummary(s)
        return s
    }

    private fun checkAlert(now: Long, bpm: Int) {
        val t = targetZone ?: return
        val z = zones.firstOrNull { it.number == t } ?: return
        val out = when { bpm > z.high -> 1; bpm < z.low -> -1; else -> 0 }
        if (out == 0) { outOfZoneSince = 0; alertText = null; return }
        if (outOfZoneSince == 0L) outOfZoneSince = now
        // Only nag after 15 s out of zone, and at most every 30 s.
        if (now - outOfZoneSince >= 15_000) {
            alertText = if (out > 0) "Above zone $t: ease off" else "Below zone $t: pick it up a little"
            if (alertsOn && now - lastAlertAt > 30_000) { lastAlertAt = now; buzz(out > 0) }
        }
    }

    private fun buzz(tooHigh: Boolean) {
        runCatching {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) appContext.getSystemService(VibratorManager::class.java).defaultVibrator
                              else @Suppress("DEPRECATION") appContext.getSystemService(Vibrator::class.java)
            val pattern = if (tooHigh) longArrayOf(0, 150, 120, 150, 120, 150) else longArrayOf(0, 600)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(if (tooHigh) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 300)
            main.postDelayed({ tone.release() }, 600)
        }
    }

    // ---- Saved sessions (on this phone only) ----------------------------------------------------------
    fun summaries(): List<LiveSessionSummary> = runCatching {
        val a = JSONArray(prefs.getString("sessions", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val z = o.getJSONArray("zones")
            LiveSessionSummary(o.getLong("start"), o.getInt("dur"), o.getInt("avg"), o.getInt("max"), o.getInt("min"),
                (0 until z.length()).map { z.getInt(it) }, o.optDouble("rmssd", Double.NaN).takeIf { !it.isNaN() },
                o.optInt("target", -1).takeIf { it > 0 }, o.optInt("inTarget"),
                o.optDouble("dist", Double.NaN).takeIf { !it.isNaN() }, o.optDouble("asc", Double.NaN).takeIf { !it.isNaN() },
                o.optString("route").ifBlank { null })
                .let { x -> x.routeId?.let { id -> Terrain(appContext).cached(id)?.ascentMeters ?: routes.cached(id)?.elevation?.ascentMeters }
                    .let { a -> if (x.routeId != null) x.copy(ascentMeters = a) else x } }
        }
    }.getOrDefault(emptyList())

    private fun saveSummary(s: LiveSessionSummary) {
        val list = listOf(s) + summaries()
        val a = JSONArray()
        list.take(50).forEach { x ->
            a.put(JSONObject().put("start", x.startEpochMs).put("dur", x.durationSec).put("avg", x.avg).put("max", x.max).put("min", x.min)
                .put("zones", JSONArray(x.secondsInZone)).put("target", x.targetZone ?: -1).put("inTarget", x.secondsInTarget)
                .apply { x.rmssdMs?.let { put("rmssd", it) }; x.distanceMeters?.let { put("dist", it) }; x.ascentMeters?.let { put("asc", it) }; x.routeId?.let { put("route", it) } })
        }
        prefs.edit().putString("sessions", a.toString()).apply()
    }

    fun deleteAll() {
        if (::appContext.isInitialized) { prefs.edit().clear().apply(); routes.deleteAll() }
        disconnect()
    }

    fun zonesFor(resting: Int, max: Int) { zones = HeartZones.zones(resting, max) }
}
