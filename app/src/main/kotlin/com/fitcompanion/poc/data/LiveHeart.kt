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

    // ---- Scanning ----------------------------------------------------------------------------------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = result.scanRecord?.deviceName ?: runCatching { d.name }.getOrNull() ?: "Heart rate sensor"
            if (found.none { it.address == d.address }) found = found + Found(name, d.address)
        }
        override fun onScanFailed(errorCode: Int) { status = Status.ERROR; statusText = "Bluetooth scan failed ($errorCode). Try again." }
    }

    fun scan() {
        val a = adapter() ?: run { status = Status.ERROR; statusText = "This phone has no Bluetooth."; return }
        if (!a.isEnabled) { status = Status.ERROR; statusText = "Turn Bluetooth on, then try again."; return }
        found = emptyList(); status = Status.SCANNING; statusText = "Looking for your band…"
        val scanner = a.bluetoothLeScanner ?: return
        runCatching {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        }.onFailure { status = Status.ERROR; statusText = "Couldn't scan: ${it.message}" }
        main.postDelayed({ stopScan() }, 12_000)
    }

    fun stopScan() {
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(scanCb) }
        if (status == Status.SCANNING) {
            status = Status.IDLE
            statusText = if (found.isEmpty()) "No band found. In Google Health, turn on Share heart rate (Always visible), keep the band close, and try again."
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

    // ---- Session + alerts ----------------------------------------------------------------------------
    fun startSession() {
        samples = emptyList(); rr.clear(); sessionStart = System.currentTimeMillis(); recording = true
        outOfZoneSince = 0; alertText = null
    }

    /** Ends the session, saves its summary on the phone and returns it. */
    fun stopSession(): LiveSessionSummary? {
        recording = false; alertText = null
        val s = LiveStats.summarise(samples, rr, zones, targetZone) ?: return null
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
                o.optInt("target", -1).takeIf { it > 0 }, o.optInt("inTarget"))
        }
    }.getOrDefault(emptyList())

    private fun saveSummary(s: LiveSessionSummary) {
        val list = listOf(s) + summaries()
        val a = JSONArray()
        list.take(50).forEach { x ->
            a.put(JSONObject().put("start", x.startEpochMs).put("dur", x.durationSec).put("avg", x.avg).put("max", x.max).put("min", x.min)
                .put("zones", JSONArray(x.secondsInZone)).put("target", x.targetZone ?: -1).put("inTarget", x.secondsInTarget)
                .apply { x.rmssdMs?.let { put("rmssd", it) } })
        }
        prefs.edit().putString("sessions", a.toString()).apply()
    }

    fun deleteAll() {
        if (::appContext.isInitialized) prefs.edit().clear().apply()
        disconnect()
    }

    fun zonesFor(resting: Int, max: Int) { zones = HeartZones.zones(resting, max) }
}
