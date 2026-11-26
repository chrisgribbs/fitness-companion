package com.fitcompanion.poc

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
class AppController private constructor(context: Context) {

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

    suspend fun refresh() = work("Syncing…") {
        if (useDemoData) {
            activities = DemoData.activities(zone = zone); daily = DemoData.daily(zone = zone)
            lastSynced = LocalDateTime.now(zone); return@work
        }
        val hcOk = repo.available()
        if (!hcOk && !cloudEnabled) error("Health Connect isn't available. Open Setup to connect Google Health instead.")
        val earliest = (challenges.map { it.startDate } + LocalDate.now(zone).minusDays(90)).min()

        var hcActs = emptyList<ActivityRecord>(); var hcDaily = emptyList<DailyMetric>()
        if (hcOk) {
            hcActs = repo.activitiesSince(earliest, zone)
            hcDaily = repo.dailyMetrics(90, zone)
            freshness = repo.freshness()
        }

        var cloudActs = emptyList<ActivityRecord>(); var cloudDaily = emptyList<DailyMetric>()
        if (cloudEnabled) {
            val token = googleTokenProvider?.invoke(false)
            if (token == null) {
                cloudStatus = CloudStatus(needsSignIn = true)
            } else {
                val days = java.time.temporal.ChronoUnit.DAYS.between(earliest, LocalDate.now(zone)) + 1
                val r = com.fitcompanion.poc.data.GoogleHealthCloud(token).read(days, zone)
                cloudActs = r.activities; cloudDaily = r.daily
                cloudStatus = CloudStatus(
                    lastRead = LocalDateTime.now(zone),
                    workouts = r.activities.size,
                    newestWorkout = r.activities.maxByOrNull { it.start }?.start?.atZone(zone),
                    problems = r.problems,
                    needsSignIn = r.problems.any { it.contains("sign-in expired") },
                )
            }
        }

        activities = withRouteClimb(mergeActivities(cloudActs, hcActs))
        fetchRecentRoutes()
        daily = mergeDaily(cloudDaily, hcDaily)
        lastSynced = LocalDateTime.now(zone)
        if (activities.isEmpty() && message == "Syncing…") message = "No runs or walks found yet."
    }

    // ---- Google Health cloud -------------------------------------------------------------------------
    /** Supplied by MainActivity: returns a fresh access token, showing Google's sign-in only if interactive. */
    var googleTokenProvider: (suspend (interactive: Boolean) -> String?)? = null

    var cloudEnabled by mutableStateOf(prefs.getBoolean("cloud_enabled", false)); private set

    data class CloudStatus(
        val lastRead: LocalDateTime? = null,
        val workouts: Int = 0,
        val newestWorkout: java.time.ZonedDateTime? = null,
        val problems: List<String> = emptyList(),
        val needsSignIn: Boolean = false,
    )
    var cloudStatus by mutableStateOf<CloudStatus?>(null); private set

    suspend fun connectCloud() {
        val token = try { googleTokenProvider?.invoke(true) } catch (e: Exception) { message = "Google sign-in failed: ${e.message}"; null }
        if (token == null) { if (message.isNullOrBlank()) message = "Google sign-in didn't complete. Check the setup steps, then try again."; return }
        cloudEnabled = true; prefs.edit().putBoolean("cloud_enabled", true).apply()
        refresh()
    }

    suspend fun signInCloudAgain() = connectCloud()

    fun disconnectCloud() {
        cloudEnabled = false; cloudStatus = null
        prefs.edit().putBoolean("cloud_enabled", false).apply()
    }

    /** Google Health cloud is the source of truth for Fitbit workouts; Health Connect fills gaps. */
    private fun mergeActivities(cloud: List<ActivityRecord>, hc: List<ActivityRecord>): List<ActivityRecord> {
        fun overlaps(a: ActivityRecord, b: ActivityRecord): Boolean {
            val start = maxOf(a.start, b.start); val end = minOf(a.end, b.end)
            if (!end.isAfter(start)) return false
            val overlap = java.time.Duration.between(start, end).seconds
            val shorter = minOf(java.time.Duration.between(a.start, a.end).seconds, java.time.Duration.between(b.start, b.end).seconds).coerceAtLeast(1)
            return overlap * 2 >= shorter
        }
        return (cloud + hc.filter { h -> cloud.none { overlaps(it, h) } }).sortedByDescending { it.start }
    }

    private fun mergeDaily(cloud: List<DailyMetric>, hc: List<DailyMetric>): List<DailyMetric> {
        if (cloud.isEmpty()) return hc
        val c = cloud.associateBy { it.date }; val h = hc.associateBy { it.date }
        return (c.keys + h.keys).sorted().map { d ->
            val a = c[d]; val b = h[d]
            DailyMetric(d, a?.steps ?: b?.steps, a?.restingHeartRate ?: b?.restingHeartRate, a?.hrvMillis ?: b?.hrvMillis, a?.sleepMinutes ?: b?.sleepMinutes)
        }
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
            // Google Health doesn't share some types (e.g. climb) with Health Connect. Old data from
            // another app (like OHealth) isn't a problem to fix, so don't raise a warning for it.
            googleHealthSharing && f.sourcePackage != GOOGLE_HEALTH_PKG -> Health.NO_DATA
            else -> Health.BEHIND
        }
        TypeStatus(f.label, h, newest, f.sourcePackage?.let(::appName), days, f.sourcePackage)
    }

    /** One plain-English line for the top of Today. */
    val syncHeadline: Pair<Health, String> get() {
        val st = typeStatuses
        if (useDemoData) return Health.UP_TO_DATE to "Showing demo data"
        if (lastSynced == null) return Health.NO_DATA to "Not checked yet — tap sync"
        if (cloudEnabled) {
            val c = cloudStatus
            when {
                c == null || c.needsSignIn -> return Health.NO_PERMISSION to "Sign in to Google Health again (Setup)"
                c.problems.isNotEmpty() -> return Health.BEHIND to "Google Health: ${c.problems.size} type${if (c.problems.size == 1) "" else "s"} couldn't be read (Setup)"
                else -> return Health.UP_TO_DATE to "Up to date from your Google Health account"
            }
        }
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

    // ---- Built-in walks and trail photos ----------------------------------------------------------
    val walkPresets = com.fitcompanion.poc.data.WalkPresets(appContext)
    val presets: List<com.fitcompanion.poc.data.WalkPresets.Preset> by lazy { walkPresets.all() }
    fun addPreset(p: com.fitcompanion.poc.data.WalkPresets.Preset, runs: Boolean, walks: Boolean) =
        save(walkPresets.toChallenge(p, runs, walks, LocalDate.now(zone)))

    val trailPhotos = com.fitcompanion.poc.data.TrailPhotos(appContext)
    var hasMapsKey by mutableStateOf(secrets.has(MAPS_KEY)); private set
    var mapsKeyMessage by mutableStateOf<String?>(null); private set
    suspend fun saveMapsKey(key: String) {
        val k = key.trim(); if (k.isBlank()) return
        mapsKeyMessage = "Testing the key…"
        val problem = trailPhotos.testGoogleKey(k)
        if (problem == null) { secrets.put(MAPS_KEY, k); hasMapsKey = true; mapsKeyMessage = "Key works. Street View photos are on." }
        else mapsKeyMessage = "Not saved: $problem"
    }
    fun removeMapsKey() { secrets.remove(MAPS_KEY); hasMapsKey = false; mapsKeyMessage = "Key removed from this phone." }
    suspend fun photoAt(lat: Double, lon: Double, streetViewFirst: Boolean = false) = runCatching {
        trailPhotos.near(lat, lon, secrets.get(MAPS_KEY),
            if (streetViewFirst) com.fitcompanion.poc.data.TrailPhotos.Source.STREET_VIEW else com.fitcompanion.poc.data.TrailPhotos.Source.WIKIMEDIA)
    }.getOrNull()

    fun save(c: ChallengeDefinition) { store.upsert(c); challenges = store.load() }
    fun delete(id: String) { store.delete(id); challenges = store.load() }
    /** Which challenge's own screen is open on the Challenges tab (set when tapping a challenge tile on Today). */
    var openChallengeId by mutableStateOf<String?>(null)
    fun pause(c: ChallengeDefinition) = save(c.pause(java.time.Instant.now()))
    fun resume(c: ChallengeDefinition) = save(c.resume(java.time.Instant.now()))
    /** Restart from right now: earlier activities (including earlier today) stop counting. */
    fun resetStart(c: ChallengeDefinition) = save(c.copy(startDate = LocalDate.now(zone), startedAt = java.time.Instant.now(), pauses = emptyList()))

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
        val client = aiClient() ?: error("Sign in with OpenRouter in the Coach tab first.")
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
    var model by mutableStateOf((prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        .let { if (OpenRouterClient.usableForChat(it)) it else DEFAULT_MODEL }); private set
    var hasKey by mutableStateOf(secrets.has(KEY_NAME)); private set
    var answer by mutableStateOf<String?>(null); private set

    fun saveAiSettings(key: String?, newModel: String) {
        if (!key.isNullOrBlank()) secrets.put(KEY_NAME, key.trim())
        model = newModel.trim().ifBlank { DEFAULT_MODEL }
        prefs.edit().putString("model", model).apply()
        hasKey = secrets.has(KEY_NAME)
    }

    fun removeKey() { secrets.remove(KEY_NAME); hasKey = false; signInMessage = null }

    // "Sign in with OpenRouter": each person connects their own OpenRouter account (and pays their own usage).
    var signingIn by mutableStateOf(false); private set
    var signInMessage by mutableStateOf<String?>(null); private set
    private val signInScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var signInJob: kotlinx.coroutines.Job? = null

    fun signInWithOpenRouter(openBrowser: (android.net.Uri) -> Unit) {
        signInJob?.cancel()
        signingIn = true; signInMessage = "Finish signing in on the OpenRouter page, then come back here."
        signInJob = signInScope.launch {
            try {
                val key = com.fitcompanion.poc.data.OpenRouterSignIn().signIn(openBrowser,
                    onApproved = { signInMessage = "Approved! Finishing sign-in…" },
                    awaitForeground = { inForeground.first { it } })
                secrets.put(KEY_NAME, key); hasKey = true
                signInMessage = "Signed in to OpenRouter. The coach is ready."
            } catch (e: kotlinx.coroutines.CancellationException) {
                signInMessage = null; throw e
            } catch (e: Exception) {
                signInMessage = if (e is kotlinx.coroutines.TimeoutCancellationException) "Sign-in timed out. Try again." else (e.message ?: "Sign-in failed. Try again.")
            } finally { signingIn = false }
        }
    }
    /** True while the app is on screen (set by MainActivity). */
    val inForeground = kotlinx.coroutines.flow.MutableStateFlow(true)
    fun cancelSignIn() { signInJob?.cancel(); signingIn = false; signInMessage = null }

    /** The exact text that would be sent with a question. Shown to the user first. */
    fun factsSheet(): String = AnalysisFacts.build(
        activities, daily, challenges.map { it to progressOf(it) }, LocalDate.now(zone), zone,
    )

    // ---- Coach chat ---------------------------------------------------------------------------------
    data class ChatTurn(
        val fromUser: Boolean, val text: String, val followUps: List<String> = emptyList(), val model: String? = null,
        val costUsd: Double? = null, val tokensIn: Int = 0, val tokensOut: Int = 0,
        val sources: List<String> = emptyList(),   // "Title|url" of research notes the coach had to hand
        val cutShort: Boolean = false,
    )
    data class Conversation(val id: String, val startedAt: Long, val title: String, val turns: List<ChatTurn>, val facts: String)

    /** Saved chats, newest first. Stored only on this phone (files/coach_chats.json). */
    var conversations by mutableStateOf(loadConversations()); private set
    private var currentId by mutableStateOf<String?>(null)
    val chat: List<ChatTurn> get() = conversations.firstOrNull { it.id == currentId }?.turns ?: emptyList()
    val currentConversation get() = conversations.firstOrNull { it.id == currentId }

    /** The user's choice about sharing the summary: until they say "always", each question shows a preview first. */
    var alwaysShareSummary by mutableStateOf(prefs.getBoolean("ai_always_share", false)); private set
    fun setAlwaysShare(on: Boolean) { alwaysShareSummary = on; prefs.edit().putBoolean("ai_always_share", on).apply() }

    fun newChat() { if (!coachBusy) { currentId = null; coachError = null } }
    fun openConversation(id: String) { if (!coachBusy) { currentId = id; coachError = null } }
    fun deleteConversation(id: String) { conversations = conversations.filter { it.id != id }; if (currentId == id) currentId = null; saveConversations() }
    fun deleteAllConversations() { conversations = emptyList(); currentId = null; saveConversations() }

    /** Facts for this conversation: frozen when the chat starts so follow-ups talk about the same numbers. */
    fun currentChatFacts(): String = currentConversation?.facts ?: (factsSheet() + planFacts() + liveFacts() + "\nNow: " +
        java.time.ZonedDateTime.now(zone).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", java.util.Locale.ENGLISH)))

    /** Training-plan status for the facts sheet, calculated here. */
    private fun planFacts(): String = buildString {
        val today = LocalDate.now(zone)
        challenges.filter { it.plan != null && !it.paused }.forEach { c ->
            val plan = c.trainingPlan ?: return@forEach
            val spec = plan.spec
            val week = plan.weekOf(today)
            val prog = com.fitcompanion.core.TrainingPlans.progress(plan, activities, zone, today)
            val cur = prog.lastOrNull()
            appendLine()
            appendLine("## Training plan: ${spec.distance.label} (${spec.level.label})" + (if (spec.eventName.isNotBlank()) " for ${spec.eventName}" else "") + " on ${spec.eventDate}")
            appendLine("- ${java.time.temporal.ChronoUnit.DAYS.between(today, spec.eventDate)} days to go; plan has ${plan.weeks.size} weeks, ${plan.runsPerWeek} runs/week, peak week ${"%.0f".format(plan.peakWeekKm)} km, longest run ${"%.1f".format(plan.peakLongKm)} km")
            if (week != null) appendLine("- This is week ${week.number} (${week.phase}): planned ${week.sessions.size} runs, ${"%.1f".format(week.km)} km, long run ${"%.1f".format(week.longKm)} km")
            if (cur != null) appendLine("- Done so far this week: ${cur.runs} runs, ${"%.1f".format(cur.km)} km, longest ${"%.1f".format(cur.longestKm)} km")
            prog.dropLast(1).takeLast(3).forEach { w -> appendLine("- Week ${w.week.number}: ${w.runs}/${w.week.sessions.size} runs, ${"%.1f".format(w.km)} of ${"%.1f".format(w.week.km)} km") }
        }
    }

    private fun putTurns(id: String, turns: List<ChatTurn>) {
        conversations = conversations.map { if (it.id == id) it.copy(turns = turns) else it }
        saveConversations()
    }

    // ---- Live answering state (the coach keeps working if you leave the screen or the app) ----
    data class Step(val text: String, val done: Boolean)
    var coachBusy by mutableStateOf(false); private set
    var coachError by mutableStateOf<String?>(null)
    var steps by mutableStateOf<List<Step>>(emptyList()); private set
    var liveText by mutableStateOf(""); private set
    var liveReasoning by mutableStateOf(""); private set
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var answerJob: kotlinx.coroutines.Job? = null
    val research = com.fitcompanion.poc.data.ResearchLibrary(appContext)
    val fx = com.fitcompanion.poc.data.ExchangeRate(appContext)

    fun cancelAnswer() { answerJob?.cancel() }

    /** Starts answering in the background and returns straight away. */
    fun askInBackground(question: String) {
        if (coachBusy) return
        answerJob = appScope.launch { ask(question) }
    }

    /** The last question has no reply (e.g. the app was closed mid-answer): ask it again. */
    fun retryLast() {
        val id = currentId ?: return
        val turns = chat
        val last = turns.lastOrNull { it.fromUser } ?: return
        putTurns(id, turns.dropLastWhile { it.fromUser })
        askInBackground(last.text)
    }

    private fun step(text: String) { steps = steps.map { it.copy(done = true) } + Step(text, false) }

    private suspend fun ask(question: String) {
        val client = aiClient() ?: run { coachError = "Sign in with OpenRouter first."; return }
        coachBusy = true; coachError = null; liveText = ""; liveReasoning = ""; steps = emptyList()
        CoachService.start(appContext)
        CoachService.clearDone(appContext)
        val newConversation = currentId == null
        val facts = currentChatFacts()
        val id = currentId ?: java.util.UUID.randomUUID().toString().also { newId ->
            conversations = listOf(Conversation(newId, System.currentTimeMillis(), question.take(80), emptyList(), facts)) + conversations
            currentId = newId
        }
        val before = chat
        putTurns(id, before + ChatTurn(true, question))
        try {
            // Tell the user exactly what the coach is looking at.
            val runs = activities.count { it.kind == com.fitcompanion.core.ActivityKind.RUN }
            val walks = activities.count { it.kind == com.fitcompanion.core.ActivityKind.WALK }
            step(if (newConversation) "Reading your data: $runs runs and $walks walks, ${daily.count { it.sleepMinutes != null }} nights of sleep, resting heart rate and HRV"
                 else "Using the same summary as earlier in this chat")
            if (challenges.isNotEmpty()) step("Checking your challenges: " + challenges.take(3).joinToString(", ") { c ->
                c.name.take(28) + " " + "%.0f%%".format(progressOf(c).percent) })
            research.refresh()
            val notes = research.relevant(question + " " + before.takeLast(2).joinToString(" ") { it.text })
            step("Looking at research: " + notes.joinToString("; ") { it.title })
            val modelName = currentModelInfo?.name ?: model.substringAfter('/')
            step("Sending a summary of your numbers to $modelName")

            val researchBlock = buildString {
                appendLine("RESEARCH NOTES (curated by the app; cite naturally, e.g. \"Australia's activity guidelines suggest…\"; don't invent other studies):")
                notes.forEachIndexed { i, n -> appendLine("[${i + 1}] ${n.title}: ${n.finding} How to use: ${n.use} Source: ${n.source}.") }
            }
            val history = before.takeLast(8).map { OpenRouterClient.Message(if (it.fromUser) "user" else "assistant", it.text) }
            val messages = listOf(OpenRouterClient.Message("system", Prompts.ANALYSIS_SYSTEM + "\n\n" + researchBlock + "\n" + facts)) + history +
                OpenRouterClient.Message("user", question)

            // Copy streamed text into the UI a few times a second.
            val reasoningBuf = StringBuffer(); val textBuf = StringBuffer()
            var thinkingShown = false; var writingShown = false
            val ticker = appScope.launch {
                while (true) {
                    liveReasoning = reasoningBuf.toString(); liveText = Prompts.splitFollowUps(textBuf.toString()).first
                    if (!thinkingShown && reasoningBuf.isNotEmpty()) { thinkingShown = true; step("$modelName is thinking it through") }
                    if (!writingShown && textBuf.isNotEmpty()) { writingShown = true; step("Writing your answer") }
                    kotlinx.coroutines.delay(120)
                }
            }
            val result = try {
                client.stream(messages, onReasoning = { reasoningBuf.append(it) }, onText = { textBuf.append(it) })
            } finally { ticker.cancel() }

            val (text, ups) = Prompts.splitFollowUps(result.text)
            if (text.isBlank()) error(if (result.finishReason == "length") "The model used up its answer length while thinking. Try again, or pick a different model."
                                      else "The model didn't send an answer. Try again or pick another model.")
            recordUsage(result.costUsd, result.promptTokens, result.completionTokens)
            putTurns(id, before + ChatTurn(true, question) + ChatTurn(false, text, ups, result.model ?: model,
                result.costUsd, result.promptTokens, result.completionTokens,
                notes.map { it.title + "|" + it.url }, cutShort = result.finishReason == "length"))
            if (!inForeground.value) CoachService.notifyDone(appContext, text.replace("*", "").take(300))
        } catch (e: kotlinx.coroutines.CancellationException) {
            coachError = "Stopped."
        } catch (e: Exception) {
            coachError = e.message ?: "Something went wrong. Try again."
            if (!inForeground.value) CoachService.notifyDone(appContext, "Couldn't finish the answer: ${coachError}")
        } finally {
            coachBusy = false; steps = emptyList(); liveText = ""; liveReasoning = ""
            CoachService.stop(appContext)
        }
    }

    // What questions actually cost on this phone, to make estimates realistic.
    val spentUsd get() = prefs.getFloat("ai_spent", 0f).toDouble()
    val answersCount get() = prefs.getInt("ai_answers", 0)
    val avgTokensIn get() = if (answersCount >= 3) prefs.getLong("ai_in", 0) / answersCount else 4500L
    val avgTokensOut get() = if (answersCount >= 3) prefs.getLong("ai_out", 0) / answersCount else 700L
    private fun recordUsage(cost: Double?, tin: Int, tout: Int) {
        if (tin <= 0) return
        prefs.edit().putFloat("ai_spent", (spentUsd + (cost ?: 0.0)).toFloat()).putInt("ai_answers", answersCount + 1)
            .putLong("ai_in", prefs.getLong("ai_in", 0) + tin).putLong("ai_out", prefs.getLong("ai_out", 0) + tout).apply()
    }

    // ---- GPS routes ------------------------------------------------------------------------------
    val routes = com.fitcompanion.poc.data.RouteStore(appContext)

    val terrain = com.fitcompanion.poc.data.Terrain(appContext)
    /** null = not asked yet. Terrain lookups send only route coordinates to the Queensland Government / Open-Meteo. */
    var terrainEnabled by mutableStateOf(terrain.enabled); private set
    fun setTerrain(on: Boolean) {
        terrain.enabled = on; terrainEnabled = on
        if (on) fetchTerrain() else activities = withRouteClimb(activities)
    }

    /** Best climb we have for a route: terrain map if looked up, otherwise (only if terrain is off) rough GPS altitude. */
    fun routeClimb(id: String): Pair<Double, String>? {
        terrain.cached(id)?.ascentMeters?.let { return it to "terrain map" }
        if (terrainEnabled == true) return null                       // waiting for the terrain lookup; don't show the rough number
        return routes.cached(id)?.elevation?.ascentMeters?.let { it to "phone GPS (rough)" }
    }

    /** Fills in climb from routes where the Fitbit gave none (never overrides a measured value). */
    private fun withRouteClimb(list: List<ActivityRecord>): List<ActivityRecord> = list.map { a ->
        if (a.isDemo || (a.ascentMeters != null && a.ascentQuality == com.fitcompanion.core.DataQuality.MEASURED)) a
        else routeClimb(a.id)?.let { a.copy(ascentMeters = it.first, ascentQuality = com.fitcompanion.core.DataQuality.ESTIMATED) }
            ?: if (routes.cached(a.id) != null) a.copy(ascentMeters = null, ascentQuality = com.fitcompanion.core.DataQuality.MISSING) else a
    }
    fun routeAdded() { activities = withRouteClimb(activities); if (terrainEnabled == true) fetchTerrain() }

    var terrainBusy by mutableStateOf(false); private set
    /** Looks up terrain climb for saved routes that don't have it yet (recent first). */
    fun fetchTerrain() {
        if (terrainEnabled != true || terrainBusy) return
        val ids = activities.filter { !it.isDemo }.map { it.id }.filter { routes.cached(it) != null && terrain.cached(it) == null }.take(60) +
            com.fitcompanion.poc.data.LiveHeart.summaries().mapNotNull { it.routeId }.filter { terrain.cached(it) == null }
        if (ids.isEmpty()) return
        terrainBusy = true
        appScope.launch {
            try { ids.forEach { id -> routes.cached(id)?.let { r -> runCatching { terrain.climb(id, r) } } } }
            finally { terrainBusy = false; activities = withRouteClimb(activities) }
        }
    }

    /** Quietly picks up routes Health Connect already shares (no prompt) for recent runs and walks. */
    private fun fetchRecentRoutes() {
        val since = java.time.Instant.now().minus(java.time.Duration.ofDays(60))
        val todo = activities.filter { !it.isDemo && it.ascentMeters == null && it.start.isAfter(since) &&
            (it.kind == com.fitcompanion.core.ActivityKind.RUN || it.kind == com.fitcompanion.core.ActivityKind.WALK) && routes.cached(it.id) == null }.take(40)
        if (todo.isEmpty()) { fetchTerrain(); return }
        appScope.launch {
            var found = 0
            todo.forEach { a -> if (routes.fromHealthConnect(a.id) is com.fitcompanion.poc.data.RouteLookup.Found) found++ }
            if (found > 0) routeAdded() else fetchTerrain()
        }
    }

    // ---- Live heart rate ---------------------------------------------------------------------------
    var showLive by mutableStateOf(false)
    var liveTarget by mutableStateOf<Int?>(null)
    fun openLive(target: Int? = null) { liveTarget = target; showLive = true }

    /** 7-day average resting heart rate from the Fitbit, for heart-rate-reserve zones. */
    val restingHrForZones: Int? get() = daily.sortedByDescending { it.date }.mapNotNull { it.restingHeartRate }.take(7)
        .takeIf { it.isNotEmpty() }?.average()?.let { kotlin.math.round(it).toInt() }
    var heartProfileVersion by mutableStateOf(0); private set
    val maxHr: Int? get() { heartProfileVersion
        return prefs.getInt("hr_max", 0).takeIf { it > 0 } ?: prefs.getInt("hr_age", 0).takeIf { it > 0 }?.let { com.fitcompanion.core.HeartZones.estimatedMax(it) } }
    val maxHrIsEstimate: Boolean get() { heartProfileVersion; return prefs.getInt("hr_max", 0) <= 0 }
    fun setHeartProfile(age: Int?, max: Int?) {
        prefs.edit().apply { if (age != null) putInt("hr_age", age); if (max != null) putInt("hr_max", max) else remove("hr_max") }.apply()
        heartProfileVersion++
    }

    private fun liveFacts(): String = buildString {
        val recent = com.fitcompanion.poc.data.LiveHeart.summaries().filter { System.currentTimeMillis() - it.startEpochMs < 28L * 86400_000 }
        if (recent.isEmpty()) return@buildString
        appendLine(); appendLine("## Live heart-rate workouts (from the band over Bluetooth), last 28 days, newest first")
        recent.take(8).forEach { w ->
            val d = java.time.Instant.ofEpochMilli(w.startEpochMs).atZone(zone).toLocalDate()
            val pct = { z: Int -> if (w.durationSec > 0) w.secondsInZone.getOrElse(z) { 0 } * 100 / w.durationSec else 0 }
            appendLine("- $d: ${w.durationSec / 60} min" + (w.distanceMeters?.let { ", %.2f km by phone GPS".format(it / 1000) } ?: "") +
                (w.ascentMeters?.let { ", climb about %.0f m (estimate)".format(it) } ?: "") +
                (if (w.avg > 0) ", avg ${w.avg} bpm, max ${w.max}; time in zones 1-5: ${(1..5).joinToString("/") { "${pct(it)}%" }}" else ", no heart rate") +
                (w.targetZone?.let { t -> "; target zone $t for ${w.secondsInTarget / 60} min" } ?: ""))
        }
    }

    // ---- Training plans ------------------------------------------------------------------------------
    data class EventInfo(val found: Boolean, val name: String, val date: LocalDate?, val location: String,
                         val distances: List<String>, val url: String, val note: String, val costUsd: Double?)

    /** Looks an event up on the web (via OpenRouter's web search; costs about half a US cent extra). */
    suspend fun lookupEvent(query: String): EventInfo {
        val client = aiClient() ?: error("Sign in with OpenRouter (Coach tab) to look events up.")
        val today = LocalDate.now(zone)
        val r = client.stream(listOf(
            OpenRouterClient.Message("system", Prompts.EVENT_LOOKUP_SYSTEM + "\nToday is $today."),
            OpenRouterClient.Message("user", query.trim())), temperature = 0.1, maxTokens = 1500, webSearch = true)
        val j = Prompts.extractJson(r.text)
        recordUsage(r.costUsd, r.promptTokens, r.completionTokens)
        val date = j.optString("date").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val ds = j.optJSONArray("distances")
        return EventInfo(j.optBoolean("found") && date != null, j.optString("name"), date, j.optString("location"),
            (0 until (ds?.length() ?: 0)).map { ds!!.getString(it) }, j.optString("url"), j.optString("note"), r.costUsd)
    }

    fun addPlan(distance: com.fitcompanion.core.RaceDistance, level: com.fitcompanion.core.PlanLevel, eventDate: LocalDate,
                eventName: String, eventUrl: String) {
        val today = LocalDate.now(zone)
        val spec = com.fitcompanion.core.PlanSpec(distance, level, eventDate, eventName.trim(), eventUrl.trim(), today)
        val plan = com.fitcompanion.core.TrainingPlans.build(distance, level, eventDate, today, eventName, eventUrl)
        save(ChallengeDefinition(
            id = java.util.UUID.randomUUID().toString(),
            name = (if (eventName.isNotBlank()) eventName.trim() else distance.label) + " · ${level.label} plan",
            metric = com.fitcompanion.core.ChallengeMetric.DISTANCE,
            goalMeters = plan.totalKm * 1000,
            includeRuns = true,
            includeWalks = distance == com.fitcompanion.core.RaceDistance.FIVE_K && level == com.fitcompanion.core.PlanLevel.BEGINNER,
            startDate = today,
            description = "${plan.weeks.size}-week ${distance.label.lowercase()} plan to ${eventDate}",
            startedAt = java.time.Instant.now(),
            plan = spec,
        ))
    }

    private val chatFile get() = java.io.File(appContext.filesDir, "coach_chats.json")
    private fun saveConversations() {
        val arr = org.json.JSONArray()
        conversations.take(50).forEach { c ->
            arr.put(org.json.JSONObject().put("id", c.id).put("startedAt", c.startedAt).put("title", c.title).put("facts", c.facts)
                .put("turns", org.json.JSONArray().apply { c.turns.forEach { t ->
                    put(org.json.JSONObject().put("user", t.fromUser).put("text", t.text).put("model", t.model ?: "")
                        .put("followUps", org.json.JSONArray(t.followUps)).put("cost", t.costUsd ?: -1.0)
                        .put("in", t.tokensIn).put("out", t.tokensOut).put("sources", org.json.JSONArray(t.sources)).put("cut", t.cutShort))
                } }))
        }
        runCatching { chatFile.writeText(arr.toString()) }
    }
    private fun loadConversations(): List<Conversation> = runCatching {
        val arr = org.json.JSONArray(chatFile.readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val ts = o.getJSONArray("turns")
            Conversation(o.getString("id"), o.getLong("startedAt"), o.getString("title"), (0 until ts.length()).map { ts.getJSONObject(it) }.map { t ->
                val f = t.optJSONArray("followUps")
                val src = t.optJSONArray("sources")
                ChatTurn(t.getBoolean("user"), t.getString("text"), (0 until (f?.length() ?: 0)).map { f!!.getString(it) }, t.optString("model").ifBlank { null },
                    t.optDouble("cost", -1.0).takeIf { it >= 0 }, t.optInt("in"), t.optInt("out"),
                    (0 until (src?.length() ?: 0)).map { src!!.getString(it) }, t.optBoolean("cut"))
            }, o.optString("facts"))
        }.filter { it.turns.isNotEmpty() }
    }.getOrDefault(emptyList())

    // Model choice
    var models by mutableStateOf<List<OpenRouterClient.ModelInfo>>(emptyList()); private set
    var modelsMessage by mutableStateOf<String?>(null); private set
    suspend fun loadModels() {
        if (models.isNotEmpty()) return
        modelsMessage = "Loading models…"
        modelsMessage = try { models = OpenRouterClient.listModels(); null } catch (e: Exception) { e.message }
    }
    fun chooseModel(id: String) {
        if (!OpenRouterClient.usableForChat(id)) return
        model = id; prefs.edit().putString("model", id).apply()
        if (message?.contains("can't be used for chat") == true) message = null
    }
    val currentModelInfo get() = models.firstOrNull { it.id == model }

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

    /** The pop-up shows each time the app is opened while an update is waiting; the Today banner and Setup badge stay until you update. */
    private var promptDismissed by mutableStateOf(false)
    private var lastUpdateCheck = 0L
    val shouldPromptUpdate: Boolean get() = update != null && !promptDismissed
    fun markUpdatePrompted() { promptDismissed = true }

    /** Called whenever the app comes to the front: re-check (at most every 30 s) and show the pop-up again. */
    suspend fun onAppOpened() {
        inForeground.value = true
        promptDismissed = false
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < 30_000) return
        lastUpdateCheck = now
        checkForUpdate()
    }

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
        store.deleteAll(); challenges = emptyList(); removeKey(); removeMapsKey(); trailPhotos.clearCache()
        appContext.getSharedPreferences("geocode_cache", Context.MODE_PRIVATE).edit().clear().apply()
        activities = emptyList(); daily = emptyList(); answer = null; proposal = null
        deleteAllConversations()
        com.fitcompanion.poc.data.LiveHeart.deleteAll()
        routes.deleteAll()
        terrain.deleteAll()
    }

    private fun aiClient(): OpenRouterClient? = secrets.get(KEY_NAME)?.let { OpenRouterClient(it, model) }

    private suspend fun work(label: String, block: suspend () -> Unit) {
        busy = true; message = label
        try { block(); if (message == label) message = null }
        catch (e: Exception) { message = e.message ?: e::class.simpleName }
        finally { busy = false; placeProgress = null }
    }

    companion object {
        @Volatile private var instance: AppController? = null
        /** One controller for the whole app, so a coach answer keeps going when screens change. */
        fun get(context: Context): AppController = instance ?: synchronized(this) {
            instance ?: AppController(context.applicationContext).also { instance = it }
        }
        const val KEY_NAME = "openrouter_key"
        const val MAPS_KEY = "google_maps_key"
        /** Google Health is the renamed Fitbit app and keeps the Fitbit package name. */
        const val GOOGLE_HEALTH_PKG = "com.fitbit.FitbitMobile"
        /** A starting suggestion only; change it in AI settings to any OpenRouter model id. */
        const val DEFAULT_MODEL = "openai/gpt-5.2"
    }
}

/** Helpers shared by the screens. */
fun ChallengeDefinition.unitWord() = if (metric == ChallengeMetric.ASCENT) "climbed" else "travelled"
