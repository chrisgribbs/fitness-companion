package com.fitcompanion.poc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.fitcompanion.poc.ui.AiScreen
import com.fitcompanion.poc.ui.ChallengesScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.ExerciseRoute
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private val results = mutableStateListOf<ProbeResult>()
    private val status = mutableStateOf("Nothing run yet.")
    private val hc by lazy { HealthConnectProbe(this) }

    private lateinit var hcPermissionLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var routeLauncher: ActivityResultLauncher<String>
    private lateinit var googleConsentLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hcPermissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            status.value = "Health Connect: ${granted.size} of ${HealthConnectProbe.PERMISSIONS.size} permissions granted."
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

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableIntStateOf(1) }
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        TabRow(selectedTabIndex = tab) {
                            listOf("Data test", "Challenges", "AI").forEachIndexed { i, t ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                            }
                        }
                        when (tab) {
                            0 -> Screen()
                            1 -> ChallengesScreen(app)
                            else -> AiScreen(app)
                        }
                    }
                }
            }
        }
    }

    private val app by lazy { AppController(this) }

    // ---- Actions -----------------------------------------------------------------------------

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

    // ---- UI ------------------------------------------------------------------------------------

    @Composable
    private fun Screen() {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Fitness data connection test", style = MaterialTheme.typography.headlineSmall)
                Text("Read-only. Nothing is uploaded except your own Google Health API requests to Google. No GPS coordinates are shown or saved.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            item { Section("1 · Health Connect (on this phone)") }
            item { Button(onClick = ::requestHealthConnect, modifier = Modifier.fillMaxWidth()) { Text("Grant Health Connect permissions") } }
            item { Button(onClick = { runHealthConnect() }, modifier = Modifier.fillMaxWidth()) { Text("Run Health Connect test") } }
            item { OutlinedButton(onClick = ::requestRoute, modifier = Modifier.fillMaxWidth()) { Text("Allow route for latest session") } }
            item { Section("2 · Google Health API (your Google account)") }
            item { Button(onClick = { runGoogle() }, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google and run API test") } }
            item { Section("Status") }
            item { Text(status.value) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = ::copyReport, modifier = Modifier.fillMaxWidth()) { Text("Copy report") }
                    OutlinedButton(onClick = ::shareReport, modifier = Modifier.fillMaxWidth()) { Text("Share report…") }
                }
            }
            items(results.sortedWith(compareBy<ProbeResult>({ it.source }, { PROBE_ITEMS.indexOf(it.item) }))) { ResultCard(it) }
        }
    }

    @Composable
    private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))

    @Composable
    private fun ResultCard(r: ProbeResult) {
        val color = when (r.status) {
            ProbeStatus.AVAILABLE_TESTED -> Color(0xFFDFF5E1)
            ProbeStatus.SUPPORTED_NOT_VERIFIED -> Color(0xFFFFF4D6)
            ProbeStatus.NOT_AVAILABLE -> Color(0xFFEDEDED)
            ProbeStatus.ERROR -> Color(0xFFFDE2E1)
            ProbeStatus.NOT_RUN -> Color.White
        }
        Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("${r.source.label} · ${r.item}", style = MaterialTheme.typography.labelLarge)
                Text(r.status.label, style = MaterialTheme.typography.bodyMedium)
                if (r.details.isNotBlank()) Text(r.details, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
