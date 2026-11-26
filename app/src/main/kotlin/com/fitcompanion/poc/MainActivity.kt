package com.fitcompanion.poc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.ExerciseRoute
import androidx.lifecycle.lifecycleScope
import com.fitcompanion.core.ActivityRecord
import com.fitcompanion.poc.ui.ActivityJournal
import com.fitcompanion.poc.ui.AiScreen
import com.fitcompanion.poc.ui.ChallengesScreen
import com.fitcompanion.poc.ui.TodayScreen
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.FitTheme
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.ScreenTitle
import com.fitcompanion.poc.ui.theme.SectionHeader
import com.fitcompanion.poc.ui.theme.SportCard
import com.fitcompanion.poc.ui.theme.StatusLine
import com.fitcompanion.poc.ui.theme.VoltButton
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private val results = mutableStateListOf<ProbeResult>()
    private val status = mutableStateOf("Nothing run yet.")
    private val hc by lazy { HealthConnectProbe(this) }
    private val app by lazy { AppController(this) }

    private lateinit var hcPermissionLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var routeLauncher: ActivityResultLauncher<String>
    private lateinit var googleConsentLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var cloudConsentLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingCloudConsent: kotlinx.coroutines.CompletableDeferred<GoogleAuth.Outcome?>? = null

    /** Gets a Google Health access token; shows Google's consent screen only when [interactive]. */
    private suspend fun googleToken(interactive: Boolean): String? {
        var outcome = GoogleAuth.authorize(this)
        if (outcome is GoogleAuth.Outcome.NeedsUserConsent) {
            if (!interactive) return null
            val wait = kotlinx.coroutines.CompletableDeferred<GoogleAuth.Outcome?>()
            pendingCloudConsent = wait
            cloudConsentLauncher.launch(IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build())
            outcome = wait.await() ?: return null
        }
        return (outcome as? GoogleAuth.Outcome.Token)?.accessToken
    }

    override fun onStop() {
        super.onStop()
        app.inForeground.value = false
    }

    override fun onStart() {
        super.onStart()
        app.inForeground.value = true
        // Every time the app is opened or brought back from the background, look for a newer build.
        lifecycleScope.launch { app.onAppOpened() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        hcPermissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            status.value = "Health Connect: ${granted.size} of ${HealthConnectProbe.PERMISSIONS.size} permissions granted."
            if (granted.isNotEmpty()) lifecycleScope.launch { app.refresh() }
        }
        routeLauncher = registerForActivityResult(ExerciseRouteRequestContract()) { route: ExerciseRoute? ->
            replace(Source.HEALTH_CONNECT, "Elevation / altitude", hc.routeAnalysis(route, hc.lastSessionElevationGained, fromConsent = true))
            replace(Source.HEALTH_CONNECT, "GPS route coordinates", ProbeResult(
                Source.HEALTH_CONNECT, "GPS route coordinates",
                if (route != null && route.route.isNotEmpty()) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
                if (route == null) "Route consent declined or no route." else "Route returned after consent: ${route.route.size} points.",
            ))
        }
        googleConsentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            lifecycleScope.launch {
                runCatching { GoogleAuth.fromConsentResult(this@MainActivity, res.data) }
                    .onSuccess { handleGoogle(it) }
                    .onFailure { status.value = "Google sign-in failed: ${it.message}" }
            }
        }

        cloudConsentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val result = runCatching { GoogleAuth.fromConsentResult(this@MainActivity, res.data) }.getOrNull()
            pendingCloudConsent?.complete(result); pendingCloudConsent = null
        }
        app.googleTokenProvider = { interactive -> googleToken(interactive) }

        // Sync on open if Health Connect is already set up.
        lifecycleScope.launch { if (app.activities.isEmpty()) runCatching { app.refresh() } }

        setContent {
            FitTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var journal by remember { mutableStateOf<ActivityRecord?>(null) }
                Scaffold(
                    containerColor = Palette.Bg,
                    bottomBar = { BottomBar(tab) { tab = it } },
                ) { padding ->
                    when (tab) {
                        0 -> TodayScreen(app, onOpenActivity = { journal = it }, onGoTo = { tab = it }, contentPadding = padding)
                        1 -> ChallengesScreen(app, padding) { journal = it }
                        2 -> AiScreen(app, padding)
                        else -> SetupScreen(padding)
                    }
                    // Solid strip behind the clock and battery icons so scrolled content doesn't run underneath them.
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxWidth()
                            .windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars)
                            .background(Palette.Bg),
                    )
                }
                journal?.let { a -> ActivityJournal(app, a) { journal = null } }

                // Unmissable update prompt (once per new build).
                @Suppress("UNUSED_VARIABLE") val jv = app.journalVersion
                val upd = app.update
                if (upd != null && app.shouldPromptUpdate) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { app.markUpdatePrompted() },
                        containerColor = Palette.Surface,
                        icon = { Icon(Icons.Rounded.SystemUpdate, null, tint = Palette.Volt) },
                        title = { Text("UPDATE AVAILABLE", style = MaterialTheme.typography.headlineSmall) },
                        text = { Text("Build ${upd.build} is ready (you have build ${app.updater.currentBuild}). It installs over this version and keeps all your data.",
                            style = MaterialTheme.typography.bodyMedium) },
                        confirmButton = { VoltButton("Update now", onClick = { app.markUpdatePrompted(); lifecycleScope.launch { app.installUpdate() } }) },
                        dismissButton = { GhostButton("Later", onClick = { app.markUpdatePrompted() }) },
                    )
                }
            }
        }
    }

    @Composable
    private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
        val items = listOf(
            Triple("Today", Icons.Rounded.Home, 0),
            Triple("Challenges", Icons.Rounded.EmojiEvents, 1),
            Triple("Coach", Icons.Rounded.AutoAwesome, 2),
            Triple("Setup", Icons.Rounded.Settings, 3),
        )
        NavigationBar(containerColor = Palette.Surface, tonalElevation = 0.dp) {
            items.forEach { (label, icon, i) ->
                NavigationBarItem(
                    selected = selected == i, onClick = { onSelect(i) },
                    icon = {
                        if (i == 3 && app.update != null) {
                            androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge(containerColor = Palette.Run) { Text("1") } }) { Icon(icon, label) }
                        } else Icon(icon, label)
                    },
                    label = { Text(label.uppercase(), style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Palette.Bg, indicatorColor = Palette.Volt,
                        selectedTextColor = Palette.Volt, unselectedIconColor = Palette.TextDim, unselectedTextColor = Palette.TextDim,
                    ),
                )
            }
        }
    }

    // ---- Data test actions ---------------------------------------------------------------------

    private fun requestHealthConnect() {
        when (hc.availability()) {
            HealthConnectClient.SDK_AVAILABLE -> hcPermissionLauncher.launch(HealthConnectProbe.PERMISSIONS)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> status.value = "Health Connect needs updating from the Play Store."
            else -> status.value = "Health Connect isn't available on this phone."
        }
    }

    private fun runHealthConnect() = lifecycleScope.launch {
        if (hc.availability() != HealthConnectClient.SDK_AVAILABLE) { status.value = "Health Connect isn't available."; return@launch }
        status.value = "Reading Health Connect…"
        runCatching { hc.run() }
            .onSuccess { r -> results.removeAll { it.source == Source.HEALTH_CONNECT }; results += r; status.value = "Health Connect test finished." }
            .onFailure { status.value = "Health Connect error: ${it.message}" }
    }

    private fun requestRoute() {
        val id = hc.latestSessionId
        if (id == null) status.value = "Run the Health Connect test first (no run/walk found yet)." else routeLauncher.launch(id)
    }

    private fun runGoogle() = lifecycleScope.launch {
        status.value = "Signing in to Google…"
        runCatching { GoogleAuth.authorize(this@MainActivity) }
            .onSuccess { handleGoogle(it) }
            .onFailure { status.value = "Google sign-in failed: ${it.message}. Check the OAuth client setup in the README." }
    }

    private suspend fun handleGoogle(outcome: GoogleAuth.Outcome) {
        when (outcome) {
            is GoogleAuth.Outcome.NeedsUserConsent ->
                googleConsentLauncher.launch(IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build())
            is GoogleAuth.Outcome.Token -> {
                status.value = "Calling Google Health API…"
                val r = GoogleHealthApiProbe(outcome.accessToken, outcome.grantedScopes).run()
                results.removeAll { it.source == Source.GOOGLE_HEALTH_API }
                results += r
                status.value = "Google Health API test finished. (Token kept in memory only.)"
            }
        }
    }

    private fun reportText() = Report.format(results, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))

    private fun copyReport() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PoC report", reportText()))
        status.value = "Report copied. Review it before pasting anywhere."
    }

    private fun shareReport() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, reportText()), "Share report"))
    }

    private fun replace(source: Source, item: String, r: ProbeResult) {
        results.removeAll { it.source == source && it.item == item }
        results += r
    }

    // ---- Setup tab -------------------------------------------------------------------------------

    @Composable
    private fun SetupScreen(contentPadding: PaddingValues) {
        var confirmWipe by remember { mutableStateOf(false) }
        var showAdvanced by remember { mutableStateOf(false) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { ScreenTitle("Setup", subtitle = "Is my data up to date?") }

            // 1. The answer to "is it synced?"
            item { com.fitcompanion.poc.ui.SyncStatusCard(app, onAllowAccess = ::requestHealthConnect) }

            // 2. What's connected.
            item { SectionHeader("Connections") }
            item {
                val hcOk = app.typeStatuses.isNotEmpty() && app.typeStatuses.none { it.health == com.fitcompanion.poc.AppController.Health.NO_PERMISSION }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.fitcompanion.poc.ui.ConnectionRow(
                        "Health Connect",
                        when {
                            app.typeStatuses.isEmpty() -> "Not connected yet — tap to allow access"
                            hcOk -> "Connected · all ${app.typeStatuses.size} data types allowed"
                            else -> "Connected · some data types not allowed — tap to fix"
                        },
                        if (app.typeStatuses.isEmpty()) false else hcOk, onClick = ::requestHealthConnect,
                    )
                    com.fitcompanion.poc.ui.ConnectionRow(
                        "Google Health app",
                        if (app.googleHealthSharing) "Sharing with Health Connect ✓ · tap to open it"
                        else "Not sharing any data yet · tap to open it and switch sharing on",
                        app.googleHealthSharing, onClick = { app.openSourceApp() },
                    )
                    com.fitcompanion.poc.ui.ConnectionRow(
                        "AI coach (OpenRouter)",
                        if (app.hasKey) "Connected · model ${app.model}" else "Not set up · add a key in the Coach tab",
                        if (app.hasKey) true else null,
                    )
                    com.fitcompanion.poc.ui.ConnectionRow(
                        "Google Health account (cloud)",
                        when {
                            !app.cloudEnabled -> "Not connected · recommended — see below"
                            app.cloudStatus?.needsSignIn == true -> "Sign-in needed · tap Sign in again below"
                            app.cloudStatus?.problems?.isNotEmpty() == true -> "Connected · some data couldn't be read"
                            else -> "Connected ✓ · reads straight from your account"
                        },
                        if (!app.cloudEnabled) null else app.cloudStatus?.let { it.problems.isEmpty() && !it.needsSignIn },
                    )
                }
            }

            item { com.fitcompanion.poc.ui.CloudCard(app) }

            // 3. App updates.
            item { SectionHeader("App") }
            item { com.fitcompanion.poc.ui.UpdateCard(app) }
            item { com.fitcompanion.poc.ui.MapsKeyCard(app) }

            // 4. Advanced tools, folded away.
            item { SectionHeader("Advanced", if (showAdvanced) "Hide" else "Show") { showAdvanced = !showAdvanced } }
            if (showAdvanced) {
                item { InspectorCard() }
                item {
                    SportCard {
                        Eyebrow("Connection test")
                        Text("Checks each data type in detail and makes a report to share.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                        GhostButton("Run Health Connect test", onClick = { runHealthConnect() }, modifier = Modifier.fillMaxWidth())
                        GhostButton("Allow route for latest run", icon = Icons.Rounded.Map, onClick = ::requestRoute, modifier = Modifier.fillMaxWidth())
                        GhostButton("Google Health cloud test", icon = Icons.Rounded.Cloud, onClick = { runGoogle() }, modifier = Modifier.fillMaxWidth())
                        Text("The cloud test needs the one-off Google Cloud setup in the README.", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                        StatusLine(status.value, Palette.Volt)
                        if (results.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            VoltButton("Copy report", icon = Icons.Rounded.ContentCopy, onClick = ::copyReport)
                            GhostButton("Share", icon = Icons.Rounded.Share, onClick = ::shareReport)
                        }
                    }
                }
                items(results.sortedWith(compareBy<ProbeResult>({ it.source }, { PROBE_ITEMS.indexOf(it.item) }))) { ResultCard(it) }
            }

            // 5. Privacy.
            item { SectionHeader("Your data") }
            item {
                SportCard(padding = 16.dp) {
                    Text("Stored on this phone only: your challenges, photos and notes, the encrypted OpenRouter key and a cache of place lookups. " +
                        "Activity data is re-read from Health Connect and kept in memory.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    GhostButton(if (confirmWipe) "Tap again to delete everything" else "Delete all local data", color = Palette.Heart,
                        icon = Icons.Rounded.DeleteForever, modifier = Modifier.fillMaxWidth(),
                        onClick = { if (confirmWipe) { app.deleteAllLocalData(); confirmWipe = false } else confirmWipe = true })
                }
            }
        }
    }

    @Composable
    private fun InspectorCard() {
        val i = app.inspection
        val f = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a")
        SportCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Palette.Volt)
                Spacer(Modifier.width(8.dp)); Eyebrow("Troubleshooting", Palette.Volt)
            }
            Text("DATA INSPECTOR", style = MaterialTheme.typography.headlineSmall)
            Text("Lists every exercise session Health Connect holds for the last 30 days, exactly as stored, and whether this app counts it.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VoltButton("Inspect my data", icon = Icons.Rounded.Search, enabled = !app.busy, onClick = { lifecycleScope.launch { app.inspect() } })
                if (i != null) GhostButton("Copy", icon = Icons.Rounded.ContentCopy, onClick = {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Inspector", app.inspectionReport()))
                    status.value = "Inspector report copied."
                })
            }
            if (i != null) {
                Text(i.permissions.entries.joinToString("   ") { "${if (it.value) "✓" else "✗"} ${it.key}" },
                    style = MaterialTheme.typography.bodySmall, color = if (i.permissions.values.all { it }) Palette.Good else Palette.Warn)
                Text("Steps today: ${i.stepsToday?.let { "%,d".format(it) } ?: "none"} · from ${i.stepsTodaySources.joinToString().ifEmpty { "no app" }}",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                Eyebrow("${i.sessions.size} exercise sessions")
                if (i.sessions.isEmpty()) Text("Health Connect has no exercise sessions for the last 30 days. Google Health isn't sharing exercise — see the tip below.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
                i.sessions.forEach { s ->
                    val counted = s.countedAs != com.fitcompanion.core.ActivityKind.OTHER
                    Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${s.typeName}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(if (counted) "COUNTED · ${s.countedAs.name}" else "NOT COUNTED", style = MaterialTheme.typography.labelSmall,
                                color = if (counted) Palette.Good else Palette.Warn)
                        }
                        Text("${s.start.atZone(app.zone).format(f)} · ${java.time.Duration.between(s.start, s.end).toMinutes()} min", style = MaterialTheme.typography.bodySmall)
                        Text("${s.distanceMeters?.let { "%.2f km".format(it / 1000) } ?: "no distance"} · climb ${s.elevationMeters?.let { "%.0f m".format(it) } ?: "none"} · route ${s.hasRoute}",
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                        Text("from ${s.sourceApp} · type code ${s.typeCode}${s.title?.takeIf { it.isNotBlank() }?.let { " · “$it”" } ?: ""}",
                            style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                    }
                }
                Text("Missing a run? If it's in the Google Health app but not listed here, Google Health hasn't shared it yet. " +
                    "In Google Health: Settings → Manage Health Connect → make sure Exercise (and Distance, Steps, Elevation) are allowed, then open Google Health once to sync and inspect again.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
    }

    @Composable
    private fun ResultCard(r: ProbeResult) {
        val color = when (r.status) {
            ProbeStatus.AVAILABLE_TESTED -> Palette.Good
            ProbeStatus.SUPPORTED_NOT_VERIFIED -> Palette.Warn
            ProbeStatus.NOT_AVAILABLE -> Palette.TextDim
            ProbeStatus.ERROR -> Palette.Heart
            ProbeStatus.NOT_RUN -> Palette.Outline
        }
        SportCard(padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(r.source.label)
                    Text(r.item, style = MaterialTheme.typography.titleSmall)
                }
            }
            Text(r.status.label, style = MaterialTheme.typography.labelMedium, color = color)
            if (r.details.isNotBlank()) Text(r.details, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
    }
}
