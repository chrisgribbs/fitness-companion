package com.fitcompanion.poc.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fitcompanion.core.PushupAnalysis
import com.fitcompanion.core.PushupMethod
import com.fitcompanion.core.PushupSet
import com.fitcompanion.core.RepCounter
import com.fitcompanion.core.RepForm
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.VoltButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A set ends by itself after this long with no new rep, so you never have to reach for the phone. */
private const val AUTO_FINISH_MS = 15_000L

private enum class Method { CAMERA, FLOOR }

/**
 * Push-up counter. Two ways, both counted entirely on the phone:
 *  - Camera: prop the phone up ~1 m away, front-on or side-on. Google's on-phone pose detection finds your shoulders,
 *    elbows and hips; nothing is recorded or sent anywhere.
 *  - Phone under chest: the proximity sensor (the one that darkens the screen during calls) counts each time you
 *    come close and go back up.
 * Counts aloud, buzzes each rep, and saves the set by itself after 15 seconds of rest.
 */
@Composable
fun PushupScreen(app: AppController, onClose: () -> Unit, onAskCoach: (String) -> Unit) {
    var method by remember { mutableStateOf<Method?>(null) }
    var result by remember { mutableStateOf<PushupSet?>(null) }
    BackHandler { if (method != null && result == null) method = null else onClose() }
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        val done: (PushupSet?) -> Unit = { s -> if (s != null) { app.addPushups(s); result = s } else method = null }
        when {
            result != null -> ResultView(result!!, onAgain = { result = null }, onClose = onClose, onAskCoach = { onAskCoach(coachQuestion(result!!)) })
            method == Method.CAMERA -> CameraCounter(onDone = done)
            method == Method.FLOOR -> FloorCounter(onDone = done)
            else -> Home(app, onPick = { method = it }, onClose = onClose)
        }
    }
}

private fun coachQuestion(s: PushupSet) =
    "I just did ${s.reps} push-ups in ${s.seconds} seconds (counted by the phone, ${s.method.label}). " +
        (if (s.notes.isNotEmpty()) "The app measured: ${s.notes.joinToString(" ")} " else "") +
        "How did that go, and how should I build on it?"

// ---------- Home: totals, the two ways to count, and history ----------

@Composable
private fun Home(app: AppController, onPick: (Method) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val zone = java.time.ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val sets = app.pushups.sortedByDescending { it.start }
    fun day(s: PushupSet) = s.start.atZone(zone).toLocalDate()
    val hasProximity = remember { (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_PROXIMITY) != null }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PUSH-UPS", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Palette.Text) }
            }
        }
        if (sets.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Stat("Today", sets.filter { day(it) == today }.sumOf { it.reps })
                Stat("7 days", sets.filter { !day(it).isBefore(today.minusDays(6)) }.sumOf { it.reps })
                Stat("Best set", sets.maxOf { it.reps })
            }
        }
        item {
            MethodCard(Icons.Rounded.Videocam, "Camera", "Recommended",
                "Prop the phone up about a metre away, front-on or side-on, so it can see your head and shoulders. Counts every rep and checks your depth (side-on also checks elbows and hips).") { onPick(Method.CAMERA) }
        }
        item {
            MethodCard(Icons.Rounded.PhoneAndroid, "Phone under your chest", if (hasProximity) "No camera" else "Not available on this phone",
                "Lay the phone face up on the floor under your chest. Each time your chest comes close to it counts one. Only counts reps that go low enough.",
                enabled = hasProximity) { onPick(Method.FLOOR) }
        }
        item {
            Text("Counted entirely on your phone. No video is saved or sent anywhere; only the number of reps, the time and short form notes are kept. The set saves by itself after 15 seconds of rest.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        if (sets.isNotEmpty()) {
            item { Eyebrow("Your sets") }
            items(sets.take(30), key = { it.start.toString() }) { s ->
                var confirm by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${s.reps} push-ups", style = MaterialTheme.typography.titleMedium)
                        Text(s.start.atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")) + " · ${s.seconds}s · ${s.method.label}",
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    }
                    if (confirm) GhostButton("Delete?", color = Palette.Heart, onClick = { app.deletePushups(s) })
                    else IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.Delete, "Delete", tint = Palette.TextDim) }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, n: Int) = Column {
    Eyebrow(label)
    Text("$n", style = MaterialTheme.typography.displaySmall)
}

@Composable
private fun MethodCard(icon: ImageVector, title: String, tag: String, body: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Surface)
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (enabled) Palette.Volt else Palette.TextDim)
            Spacer(Modifier.width(10.dp))
            Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = if (enabled) Palette.Text else Palette.TextDim, modifier = Modifier.weight(1f))
            Text(tag, style = MaterialTheme.typography.labelMedium, color = if (enabled) Palette.Volt else Palette.TextDim)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
    }
}

// ---------- Shared counting state: count aloud, buzz, auto-finish ----------

private class SetState(private val ctx: Context, private val speaker: Speaker) {
    var count by mutableIntStateOf(0)
    var firstMoveMs by mutableLongStateOf(0L)
    var lastRepMs by mutableLongStateOf(0L)
    val forms = mutableListOf<RepForm>()

    fun rep(form: RepForm) {
        val now = SystemClock.elapsedRealtime()
        if (count == 0) firstMoveMs = now - form.durationMs
        count++; lastRepMs = now; forms += form
        speaker.speak("$count")
        buzz(ctx)
    }

    fun toSet(method: PushupMethod): PushupSet? {
        if (count == 0) return null
        val secs = ((lastRepMs - firstMoveMs) / 1000).toInt().coerceAtLeast(1)
        return PushupSet(Instant.now().minusMillis(SystemClock.elapsedRealtime() - firstMoveMs), count, secs, method,
            PushupAnalysis.notes(forms, method))
    }
}

private fun buzz(ctx: Context) {
    val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    runCatching { v?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)) }
}

/** Ends the set after [AUTO_FINISH_MS] of rest; returns seconds left once the countdown is close, else null. */
@Composable
private fun autoFinish(state: SetState, finish: () -> Unit): Int? {
    var left by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            if (state.count == 0) { left = null; continue }
            val rest = SystemClock.elapsedRealtime() - state.lastRepMs
            left = if (rest > AUTO_FINISH_MS - 6_000) ((AUTO_FINISH_MS - rest) / 1000 + 1).toInt() else null
            if (rest >= AUTO_FINISH_MS) { finish(); break }
        }
    }
    return left
}

@Composable
private fun BigCount(state: SetState, position: Float, status: String, statusColor: androidx.compose.ui.graphics.Color, saveIn: Int?, onDone: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, style = MaterialTheme.typography.labelLarge, color = statusColor,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Palette.Bg.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 8.dp))
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel", tint = Palette.Text) }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${state.count}", fontSize = 132.sp, fontWeight = FontWeight.Black, color = Palette.Volt, lineHeight = 132.sp,
                modifier = Modifier.weight(1f))
            // Up/down gauge: shows the phone can see you moving.
            Box(Modifier.width(14.dp).height(160.dp).clip(RoundedCornerShape(50)).background(Palette.Bg.copy(alpha = 0.7f))) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(position.coerceIn(0.05f, 1f))
                    .clip(RoundedCornerShape(50)).background(Palette.Volt))
            }
        }
        Text(if (saveIn != null) "Saving in $saveIn s… do another rep to keep going" else if (state.count == 0) "push-ups · start when you're ready" else "push-ups",
            style = MaterialTheme.typography.titleMedium, color = Palette.Text,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Bg.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 4.dp))
        Spacer(Modifier.height(14.dp))
        VoltButton(if (state.count == 0) "Cancel" else "Done", onClick = if (state.count == 0) onCancel else onDone, modifier = Modifier.fillMaxWidth())
    }
}

// ---------- Camera ----------

/** Turns poses into reps. Works out front-on vs side-on from where your body is in the picture. */
private class PoseCounter(val onRep: (RepForm) -> Unit) {
    var status by mutableStateOf("Looking for you…")
    var ready by mutableStateOf(false)
    var position by mutableStateOf(1f)
    var view by mutableStateOf<PushupMethod?>(null)
    private var frontVotes = 0
    private var sideVotes = 0
    private var counter: RepCounter? = null
    private var minElbow: Double? = null
    private var minBody: Double? = null
    private var maxTilt: Double? = null

    private fun Pose.at(type: Int) = getPoseLandmark(type)?.takeIf { it.inFrameLikelihood > 0.5f }?.position

    private fun angle(a: android.graphics.PointF, b: android.graphics.PointF, c: android.graphics.PointF): Double {
        val v1x = a.x - b.x; val v1y = a.y - b.y; val v2x = c.x - b.x; val v2y = c.y - b.y
        val d = hypot(v1x, v1y) * hypot(v2x, v2y)
        if (d == 0f) return 180.0
        return Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / d).toDouble().coerceIn(-1.0, 1.0)))
    }

    fun onPose(p: Pose, w: Int, h: Int, t: Long) {
        val ls = p.at(PoseLandmark.LEFT_SHOULDER); val rs = p.at(PoseLandmark.RIGHT_SHOULDER)
        val nose = p.at(PoseLandmark.NOSE)
        if (ls == null && rs == null && nose == null) {
            status = if (ready) "Lost you — stay in view" else "Can't see you yet. Prop the phone up about a metre away so your head and shoulders are in view."
            return
        }
        // Each side: shoulder, elbow, wrist, hip, ankle (whichever side the camera sees best).
        fun side(left: Boolean) = listOf(
            p.at(if (left) PoseLandmark.LEFT_SHOULDER else PoseLandmark.RIGHT_SHOULDER),
            p.at(if (left) PoseLandmark.LEFT_ELBOW else PoseLandmark.RIGHT_ELBOW),
            p.at(if (left) PoseLandmark.LEFT_WRIST else PoseLandmark.RIGHT_WRIST),
            p.at(if (left) PoseLandmark.LEFT_HIP else PoseLandmark.RIGHT_HIP),
            p.at(if (left) PoseLandmark.LEFT_ANKLE else PoseLandmark.RIGHT_ANKLE))
        val l = side(true); val r = side(false)
        val best = if (l.count { it != null } >= r.count { it != null }) l else r
        val (sh, el, wr, hip, ank) = best

        if (view == null) {
            // Side-on: your body stretches across the picture (shoulder to ankle far apart sideways).
            if (sh != null && hip != null && (ank != null || hip.x != sh.x) &&
                abs((ank ?: hip).x - sh.x) > (if (ank != null) 0.35f else 0.2f) * w) sideVotes++
            else if (ls != null && rs != null || nose != null) frontVotes++
            if (sideVotes + frontVotes >= 12) {
                view = if (sideVotes > frontVotes) PushupMethod.CAMERA_SIDE else PushupMethod.CAMERA_FRONT
                counter = if (view == PushupMethod.CAMERA_SIDE) RepCounter(minRange = 0.14) else RepCounter(minRange = 0.035)
                ready = true
            } else { status = "Got you — hold still a moment…"; return }
        }
        val c = counter ?: return
        val side = view == PushupMethod.CAMERA_SIDE
        val elbowAngle = if (sh != null && el != null && wr != null) angle(sh, el, wr) else null
        // Signal (bigger = higher up). Side-on: straighter elbow. Front-on: how high your shoulders are in the picture.
        val value: Double = if (side) {
            elbowAngle?.div(180.0) ?: run { status = "Side-on · keep your arm in view"; return }
        } else {
            val y = when { ls != null && rs != null -> (ls.y + rs.y) / 2; ls != null -> ls.y; rs != null -> rs.y; else -> nose!!.y }
            -(y / h).toDouble()
        }
        status = if (side) "Side-on · counting, checking elbows and hips" else "Front-on · counting and checking depth"

        if (c.isDown) {
            elbowAngle?.let { minElbow = min(minElbow ?: 180.0, it) }
            if (side && sh != null && hip != null && ank != null) minBody = min(minBody ?: 180.0, angle(sh, hip, ank))
            if (!side && ls != null && rs != null) {
                val width = abs(ls.x - rs.x)
                if (width > 0) { val tilt = ((ls.y - rs.y) / width).toDouble(); maxTilt = if (abs(tilt) > abs(maxTilt ?: 0.0)) tilt else maxTilt }
            }
        }
        val rep = c.feed(t, value)
        position = c.position.toFloat()
        if (rep != null) {
            onRep(RepForm(rep.depth, rep.durationMs, if (side) minElbow else null, if (side) minBody else null, if (!side) maxTilt else null))
            minElbow = null; minBody = null; maxTilt = null
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraCounter(onDone: (PushupSet?) -> Unit) {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> allowed = ok; denied = !ok }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    if (!allowed) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("CAMERA NEEDED", style = MaterialTheme.typography.headlineMedium)
            Text(if (denied) "The camera counter needs camera access. Nothing is recorded or sent: the phone only works out where your shoulders and elbows are. You can allow it in Settings → Apps → Fitness Companion → Permissions."
                 else "Asking for camera access…", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            if (denied) GhostButton("Back", onClick = { onDone(null) })
        }
        return
    }

    val speaker = rememberSpeaker()
    val state = remember { SetState(ctx, speaker) }
    val poses = remember { PoseCounter { state.rep(it) } }
    var announced by remember { mutableStateOf(false) }
    LaunchedEffect(poses.ready) { if (poses.ready && !announced) { announced = true; speaker.speak("Ready. Start when you like.") } }
    val finish = {
        val set = state.toSet(poses.view ?: PushupMethod.CAMERA_FRONT)
        set?.let { speaker.speak("Saved. ${it.reps} push-ups.") }
        onDone(set)
    }
    val saveIn = autoFinish(state, finish)

    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val detector = PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                val rot = proxy.imageInfo.rotationDegrees
                val w = if (rot % 180 == 0) proxy.width else proxy.height
                val h = if (rot % 180 == 0) proxy.height else proxy.width
                val t = SystemClock.elapsedRealtime()
                detector.process(InputImage.fromMediaImage(media, rot))
                    .addOnSuccessListener { pose -> poses.onPose(pose, w, h, t) }
                    .addOnCompleteListener { proxy.close() }
            }
            val camera = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, camera, preview, analysis) }
                .onFailure { poses.status = "Couldn't start the camera: ${it.message}" }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { future.get().unbindAll() }
            detector.close(); executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        BigCount(state, poses.position, poses.status, if (poses.ready) Palette.Volt else Palette.Warn, saveIn,
            onDone = finish, onCancel = { onDone(null) })
    }
}

// ---------- Phone under chest (proximity sensor) ----------

@Composable
private fun FloorCounter(onDone: (PushupSet?) -> Unit) {
    val ctx = LocalContext.current
    val speaker = rememberSpeaker()
    val state = remember { SetState(ctx, speaker) }
    var countdown by remember { mutableStateOf<Int?>(5) }
    var near by remember { mutableStateOf(false) }
    val counter = remember { RepCounter(minRange = 0.5, minRepMs = 400, windowMs = Long.MAX_VALUE / 4, smoothing = 1.0) }
    val finish = {
        val set = state.toSet(PushupMethod.FLOOR)
        set?.let { speaker.speak("Saved. ${it.reps} push-ups.") }
        onDone(set)
    }
    val saveIn = autoFinish(state, finish)

    LaunchedEffect(Unit) {
        delay(600)
        speaker.speak("Put the phone face up under your chest. Starting in five.")
        for (i in 5 downTo 1) { countdown = i; delay(1000) }
        countdown = null
        speaker.speak("Go")
    }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        var downAt = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (countdown != null || sensor == null) return
                val isNear = e.values[0] < min(sensor.maximumRange, 5f)
                near = isNear
                val t = SystemClock.elapsedRealtime()
                if (isNear) downAt = t
                counter.feed(t, if (isNear) 0.0 else 1.0)?.let { rep -> state.rep(RepForm(1.0, max(rep.durationMs, t - downAt))) }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        onDispose { sm.unregisterListener(listener) }
    }

    val cd = countdown
    if (cd != null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("GET IN POSITION", style = MaterialTheme.typography.headlineMedium)
            Text("Lay the phone face up on the floor under your chest, then get into push-up position. Each time your chest comes within a few centimetres of the phone counts as one.",
                style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim)
            Spacer(Modifier.weight(1f))
            Text("$cd", fontSize = 140.sp, fontWeight = FontWeight.Black, color = Palette.Volt, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.weight(1f))
            GhostButton("Cancel", onClick = { onDone(null) }, modifier = Modifier.fillMaxWidth())
        }
    } else {
        BigCount(state, if (near) 0f else 1f, if (near) "Down" else "Up · counting", Palette.Volt, saveIn, onDone = finish, onCancel = { onDone(null) })
    }
}

// ---------- Result ----------

@Composable
private fun ResultView(s: PushupSet, onAgain: () -> Unit, onClose: () -> Unit, onAskCoach: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Eyebrow("Set saved")
        Text("${s.reps}", fontSize = 120.sp, fontWeight = FontWeight.Black, color = Palette.Volt, lineHeight = 120.sp)
        Text("push-ups in ${s.seconds} seconds · ${s.method.label}", style = MaterialTheme.typography.titleMedium)
        s.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim) }
        if (s.method == PushupMethod.FLOOR)
            Text("The under-chest counter can count and time reps but can't see your form. Use the camera for depth and form checks.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        Spacer(Modifier.weight(1f))
        VoltButton("Another set", onClick = onAgain, modifier = Modifier.fillMaxWidth())
        GhostButton("Ask the coach about this set", onClick = onAskCoach, modifier = Modifier.fillMaxWidth())
        Text("Sends only these numbers and notes to your chosen AI, no video.", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        GhostButton("Close", onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}
