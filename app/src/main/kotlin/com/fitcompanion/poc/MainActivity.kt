package com.fitcompanion.poc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                }
                journal?.let { a -> ActivityJournal(app, a) { journal = null } }
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
                    icon = { Icon(icon, label) },
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
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { ScreenTitle("Setup", subtitle = "Connections & data test") }
            item {
                SportCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.HealthAndSafety, null, tint = Palette.Walk)
                        Spacer(Modifier.width(8.dp)); Eyebrow("Step 1 · on this phone", Palette.Walk)
                    }
                    Text("HEALTH CONNECT", style = MaterialTheme.typography.headlineSmall)
                    Text("Reads what the Google Health app copies onto your phone. Read-only; no login needed.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    VoltButton("Grant permissions", onClick = ::requestHealthConnect, modifier = Modifier.fillMaxWidth())
                    GhostButton("Run data test", onClick = { runHealthConnect() }, modifier = Modifier.fillMaxWidth())
                    GhostButton("Allow route for latest run", icon = Icons.Rounded.Map, onClick = ::requestRoute, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                SportCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Cloud, null, tint = Palette.Sleep)
                        Spacer(Modifier.width(8.dp)); Eyebrow("Step 2 · optional", Palette.Sleep)
                    }
                    Text("GOOGLE HEALTH API", style = MaterialTheme.typography.headlineSmall)
                    Text("Full history and run exports. Needs the one-off Google Cloud setup in the README.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    GhostButton("Sign in & run API test", onClick = { runGoogle() }, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                StatusLine(status.value, Palette.Volt)
            }
            if (results.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VoltButton("Copy report", icon = Icons.Rounded.ContentCopy, onClick = ::copyReport)
                        GhostButton("Share", icon = Icons.Rounded.Share, onClick = ::shareReport)
                    }
                }
                item { SectionHeader("Results") }
                items(results.sortedWith(compareBy<ProbeResult>({ it.source }, { PROBE_ITEMS.indexOf(it.item) }))) { ResultCard(it) }
            }
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
