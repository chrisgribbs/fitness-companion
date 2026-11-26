package com.fitcompanion.poc

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fitcompanion.core.ActivityRecord
import com.fitcompanion.core.AnalysisFacts
import com.fitcompanion.core.ChallengeDefinition
import com.fitcompanion.core.ChallengeEngine
import com.fitcompanion.core.ChallengeMetric
import com.fitcompanion.core.ChallengeProgress
import com.fitcompanion.core.ChallengeValidator
import com.fitcompanion.core.DailyMetric
import com.fitcompanion.core.Everest
import com.fitcompanion.core.JourneyRoute
import com.fitcompanion.core.ValidationResult
import com.fitcompanion.core.WaypointCheck
import com.fitcompanion.poc.ai.OpenRouterClient
import com.fitcompanion.poc.ai.PlaceChecker
import com.fitcompanion.poc.ai.Prompts
import com.fitcompanion.poc.data.ActivityRepository
import com.fitcompanion.poc.data.ChallengeStore
import com.fitcompanion.poc.data.DemoData
import com.fitcompanion.poc.data.SecretStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Screen state for the Challenges and AI tabs. Plain Compose state; no background work. */
class AppController(context: Context) {
    private val appContext = context.applicationContext
    private val repo = ActivityRepository(appContext)
    private val store = ChallengeStore(appContext)
    private val secrets = SecretStore(appContext)
    private val places = PlaceChecker(appContext)
    val photos = com.fitcompanion.poc.data.PhotoStore(appContext)
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val zone: ZoneId = ZoneId.systemDefault()

    // ---- Data -------------------------------------------------------------------------------
    var useDemoData by mutableStateOf(false); private set
    var activities by mutableStateOf<List<ActivityRecord>>(emptyList()); private set
    var daily by mutableStateOf<List<DailyMetric>>(emptyList()); private set
    var lastSynced by mutableStateOf<LocalDateTime?>(null); private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set

    var challenges by mutableStateOf(store.load()); private set

    fun progressOf(c: ChallengeDefinition): ChallengeProgress =
        ChallengeEngine.progress(c, activities, LocalDate.now(zone), zone)

    suspend fun refresh() = work("Reading Health Connect…") {
        if (useDemoData) {
            activities = DemoData.activities(zone = zone); daily = DemoData.daily(zone = zone)
        } else {
            if (!repo.available()) error("Health Connect isn't available. Use the Data test tab to set it up.")
            val earliest = (challenges.map { it.startDate } + LocalDate.now(zone).minusDays(90)).min()
            activities = repo.activitiesSince(earliest, zone)
            daily = repo.dailyMetrics(90, zone)
            freshness = repo.freshness()
            if (activities.isEmpty()) message = "No runs or walks found. Check Health Connect permissions on the Data test tab."
        }
        lastSynced = LocalDateTime.now(zone)
    }

    // ---- Sync status ------------------------------------------------------------------------------
    var freshness by mutableStateOf<List<ActivityRepository.Freshness>>(emptyList()); private set

    enum class Health { UP_TO_DATE, BEHIND, NO_DATA, NO_PERMISSION }

    data class TypeStatus(val label: String, val health: Health, val newest: java.time.ZonedDateTime?, val source: String?, val daysBehind: Long?, val sourcePackage: String? = null)

    /** Up to date = newest record is from today or yesterday (data only arrives after you sync your tracker). */
    val typeStatuses: List<TypeStatus> get() = freshness.map { f ->
        val newest = f.newest?.atZone(zone)
        val days = newest?.let { java.time.temporal.ChronoUnit.DAYS.between(it.toLocalDate(), LocalDate.now(zone)) }
        val h = when {
            !f.permitted -> Health.NO_PERMISSION
            newest == null -> Health.NO_DATA
            days != null && days <= 1 -> Health.UP_TO_DATE
            else -> Health.BEHIND
        }
        TypeStatus(f.label, h, newest, f.sourcePackage?.let(::appName), days, f.sourcePackage)
    }

    /** One plain-English line for the top of Today. */
    val syncHeadline: Pair<Health, String> get() {
        val st = typeStatuses
        if (useDemoData) return Health.UP_TO_DATE to "Showing demo data"
        if (lastSynced == null) return Health.NO_DATA to "Not checked yet — tap sync"
        if (st.isEmpty()) return Health.NO_DATA to "Couldn't read Health Connect"
        val behind = st.filter { it.health == Health.BEHIND }
        val missing = st.filter { it.health == Health.NO_PERMISSION }
        return when {
            behind.isEmpty() && missing.isEmpty() -> Health.UP_TO_DATE to "All up to date"
            behind.isNotEmpty() -> {
                val oldest = behind.minByOrNull { it.newest!! }!!
                val who = behind.mapNotNull { it.source }.distinct().joinToString(" / ").ifEmpty { "The source app" }
                Health.BEHIND to "${behind.joinToString { it.label.substringBefore(" (") }} stop at ${oldest.newest!!.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))} — $who hasn't shared newer data"
            }
            else -> Health.NO_PERMISSION to "${missing.size} data type${if (missing.size == 1) "" else "s"} not allowed — tap Setup"
        }
    }

    /** Package that wrote the most data, e.g. Google Health — used for the "open it to sync" button. */
    val mainSourcePackage: String? get() = freshness.filter { it.label != "Steps" }.mapNotNull { it.sourcePackage }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: freshness.firstNotNullOfOrNull { it.sourcePackage }

    fun appName(pkg: String): String = when {
        pkg == GOOGLE_HEALTH_PKG -> "Google Health"
        pkg == "com.google.android.apps.fitness" -> "Google Fit"
        pkg.startsWith("com.heytap.health") -> "OHealth"
        pkg == "com.google.android.apps.healthdata" || pkg == "android" || pkg.startsWith("com.android.healthconnect.phone") -> "This phone's step counter"
        else -> runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /** True if any data type currently comes from Google Health (the Fitbit Air's app). */
    val googleHealthSharing: Boolean get() = freshness.any { it.sourcePackage == GOOGLE_HEALTH_PKG }

    /** Opens Google Health so you can switch on its Health Connect sharing. */
    fun openSourceApp(): Boolean {
        val pkg = GOOGLE_HEALTH_PKG
        val intent = appContext.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        appContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun openHealthConnectSettings() {
        runCatching {
            appContext.startActivity(android.content.Intent(androidx.health.connect.client.HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    suspend fun setDemo(on: Boolean) { useDemoData = on; refresh() }

    // ---- Challenges ---------------------------------------------------------------------------
    fun addEverest(runs: Boolean, walks: Boolean) {
        save(Everest.definition(UUID.randomUUID().toString(), LocalDate.now(zone).with(java.time.DayOfWeek.MONDAY), runs, walks))
    }

    fun save(c: ChallengeDefinition) { store.upsert(c); challenges = store.load() }
    fun delete(id: String) { store.delete(id); challenges = store.load() }
    fun resetStart(c: ChallengeDefinition) = save(c.copy(startDate = LocalDate.now(zone)))

    /** In-progress AI challenge, shown for review before saving. */
    data class ChallengeProposal(
        val prompt: String,
        val definition: ChallengeDefinition?,
        val warnings: List<String>,
        val errors: List<String>,
        val checks: List<WaypointCheck> = emptyList(),
        val checked: Boolean = false,
    )
    var proposal by mutableStateOf<ChallengeProposal?>(null)
    var placeProgress by mutableStateOf<String?>(null); private set

    /** Sends ONLY the user's own description to OpenRouter. No health data is included. */
    suspend fun designChallenge(description: String) = work("Asking the AI to design your challenge…") {
        val client = aiClient() ?: error("Add your OpenRouter key in the AI tab first.")
        var reply = client.chat(listOf(
            OpenRouterClient.Message("system", Prompts.CHALLENGE_SYSTEM),
            OpenRouterClient.Message("user", description),
        ), temperature = 0.4, maxTokens = 3000)
        var result = ChallengeValidator.validate(Prompts.toDraft(Prompts.extractJson(reply.text)), UUID.randomUUID().toString(), LocalDate.now(zone))
        if (result is ValidationResult.Invalid) {
            // One retry, telling the model exactly what was wrong.
            reply = client.chat(listOf(
                OpenRouterClient.Message("system", Prompts.CHALLENGE_SYSTEM),
                OpenRouterClient.Message("user", description),
                OpenRouterClient.Message("assistant", reply.text),
                OpenRouterClient.Message("user", "That had problems: ${result.errors.joinToString("; ")}. Return the corrected JSON only."),
            ), temperature = 0.2, maxTokens = 3000)
            result = ChallengeValidator.validate(Prompts.toDraft(Prompts.extractJson(reply.text)), UUID.randomUUID().toString(), LocalDate.now(zone))
        }
        proposal = when (result) {
            is ValidationResult.Ok -> ChallengeProposal(description, result.definition, result.warnings, emptyList())
            is ValidationResult.Invalid -> ChallengeProposal(description, null, emptyList(), result.errors)
        }
    }

    /** Looks each waypoint up on OpenStreetMap (place names only) and re-measures the route. */
    suspend fun checkPlaces() = work("Checking places on OpenStreetMap…") {
        val p = proposal ?: return@work
        val def = p.definition ?: return@work
        val checks = def.route.mapIndexed { i, w ->
            placeProgress = "Checking ${i + 1} of ${def.route.size}: ${w.name}"
            places.check(w.name, w.latitude, w.longitude)
        }
        placeProgress = null
        val resolved = checks.map { it.resolved() }
        val route = JourneyRoute(resolved)
        val updated = def.copy(route = resolved, goalMeters = route.totalMeters,
            milestones = route.waypointMilestones() + def.milestones.filter { m -> def.route.none { it.name == m.name } && m.atMeters <= route.totalMeters })
        proposal = p.copy(definition = updated, checks = checks, checked = true)
    }

    fun acceptProposal() { proposal?.definition?.let { save(it.copy(milestones = it.milestones.sortedBy { m -> m.atMeters })) }; proposal = null }

    // ---- AI analysis ---------------------------------------------------------------------------
    var model by mutableStateOf(prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL); private set
    var hasKey by mutableStateOf(secrets.has(KEY_NAME)); private set
    var answer by mutableStateOf<String?>(null); private set

    fun saveAiSettings(key: String?, newModel: String) {
        if (!key.isNullOrBlank()) secrets.put(KEY_NAME, key.trim())
        model = newModel.trim().ifBlank { DEFAULT_MODEL }
        prefs.edit().putString("model", model).apply()
        hasKey = secrets.has(KEY_NAME)
    }

    fun removeKey() { secrets.remove(KEY_NAME); hasKey = false }

    /** The exact text that would be sent with a question. Shown to the user first. */
    fun factsSheet(): String = AnalysisFacts.build(
        activities, daily, challenges.map { it to progressOf(it) }, LocalDate.now(zone), zone,
    )

    suspend fun ask(question: String, facts: String) = work("Asking the AI…") {
        val client = aiClient() ?: error("Add your OpenRouter key first.")
        val reply = client.chat(listOf(
            OpenRouterClient.Message("system", Prompts.ANALYSIS_SYSTEM),
            OpenRouterClient.Message("user", "$facts\n\nQUESTION: $question"),
        ))
        answer = reply.text.trim() + "\n\n— AI-generated by ${reply.model ?: model} from the facts sheet above. Not medical advice."
    }

    // ---- App updates ------------------------------------------------------------------------------
    val updater = com.fitcompanion.poc.data.Updater(appContext)
    var update by mutableStateOf<com.fitcompanion.poc.data.Updater.Available?>(null); private set
    var updateProgress by mutableStateOf<Float?>(null); private set
    var updateMessage by mutableStateOf<String?>(null); private set

    /** Quiet check (on app open): only shows something if a newer build exists. */
    suspend fun checkForUpdate(manual: Boolean = false) {
        try {
            update = updater.check()
            updateMessage = if (update == null && manual) "You're on the latest build (${updater.currentBuild})." else null
        } catch (e: Exception) {
            if (manual) updateMessage = e.message ?: "Couldn't check for updates."
        }
    }

    /** The pop-up shows once per new build; the Today banner and Setup badge stay until you update. */
    val shouldPromptUpdate: Boolean get() = update?.let { prefs.getInt("update_prompted", 0) < it.build } ?: false
    fun markUpdatePrompted() { update?.let { prefs.edit().putInt("update_prompted", it.build).apply() }; journalChanged() }

    suspend fun installUpdate() {
        val a = update ?: return
        if (!updater.canInstall()) {
            updateMessage = "Allow \"Install unknown apps\" for Fitness Companion, then come back and tap Update again."
            updater.openInstallPermissionSettings(); return
        }
        try {
            updateProgress = 0f; updateMessage = "Downloading build ${a.build}…"
            val file = updater.download(a) { updateProgress = it }
            updateProgress = null; updateMessage = null
            updater.install(file)
        } catch (e: Exception) {
            updateProgress = null; updateMessage = e.message ?: "Update failed. Try again."
        }
    }

    // ---- Data inspector ------------------------------------------------------------------------
    var inspection by mutableStateOf<ActivityRepository.Inspection?>(null); private set

    suspend fun inspect() = work("Inspecting Health Connect…") {
        if (!repo.available()) error("Health Connect isn't available on this phone.")
        inspection = repo.inspect(30, zone)
    }

    /** Plain-text version for pasting into a chat. No coordinates; dates, types, sources and totals only. */
    fun inspectionReport(): String {
        val i = inspection ?: return "No inspection yet."
        val f = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
        return buildString {
            appendLine("DATA INSPECTOR — Health Connect, last 30 days (${java.time.LocalDateTime.now(zone).format(f)})")
            appendLine("Permissions: " + i.permissions.entries.joinToString { "${it.key} ${if (it.value) "✓" else "✗"}" })
            appendLine("Steps today: ${i.stepsToday ?: "none"} from ${i.stepsTodaySources.ifEmpty { listOf("no app") }.joinToString()}")
            appendLine("Distance today from: ${i.distanceTodaySources.ifEmpty { listOf("no app") }.joinToString()}")
            appendLine("Exercise sessions: ${i.sessions.size}")
            i.sessions.forEach { s ->
                appendLine("- ${s.start.atZone(zone).format(f)} · ${java.time.Duration.between(s.start, s.end).toMinutes()} min · ${s.typeName} (code ${s.typeCode})" +
                    " · ${s.distanceMeters?.let { "%.2f km".format(it / 1000) } ?: "no distance"}" +
                    " · climb ${s.elevationMeters?.let { "%.0f m".format(it) } ?: "none"} · route ${s.hasRoute}" +
                    " · from ${s.sourceApp} · ${if (s.countedAs == com.fitcompanion.core.ActivityKind.OTHER) "NOT counted as run/walk" else "counted as ${s.countedAs.name.lowercase()}"}" +
                    (s.title?.takeIf { it.isNotBlank() }?.let { " · title '$it'" } ?: ""))
            }
        }
    }

    // ---- Photos and notes ------------------------------------------------------------------------
    /** Bumped whenever photos or notes change, so screens re-read them. */
    var journalVersion by mutableStateOf(0); private set
    fun journalChanged() { journalVersion++ }
    fun setNote(activityId: String, text: String) { photos.setNote(activityId, text); journalChanged() }

    val recentActivities: List<ActivityRecord> get() = activities.sortedByDescending { it.start }

    fun deleteAllLocalData() {
        photos.deleteAll(); journalChanged()
        store.deleteAll(); challenges = emptyList(); removeKey()
        appContext.getSharedPreferences("geocode_cache", Context.MODE_PRIVATE).edit().clear().apply()
        activities = emptyList(); daily = emptyList(); answer = null; proposal = null
    }

    private fun aiClient(): OpenRouterClient? = secrets.get(KEY_NAME)?.let { OpenRouterClient(it, model) }

    private suspend fun work(label: String, block: suspend () -> Unit) {
        busy = true; message = label
        try { block(); if (message == label) message = null }
        catch (e: Exception) { message = e.message ?: e::class.simpleName }
        finally { busy = false; placeProgress = null }
    }

    companion object {
        const val KEY_NAME = "openrouter_key"
        /** Google Health is the renamed Fitbit app and keeps the Fitbit package name. */
        const val GOOGLE_HEALTH_PKG = "com.fitbit.FitbitMobile"
        /** A starting suggestion only; change it in AI settings to any OpenRouter model id. */
        const val DEFAULT_MODEL = "openai/gpt-5.2"
    }
}

/** Helpers shared by the screens. */
fun ChallengeDefinition.unitWord() = if (metric == ChallengeMetric.ASCENT) "climbed" else "travelled"
