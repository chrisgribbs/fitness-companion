package com.fitcompanion.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.ui.theme.ChoiceChip
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.ScreenTitle
import com.fitcompanion.poc.ui.theme.SectionHeader
import com.fitcompanion.poc.ui.theme.SportCard
import com.fitcompanion.poc.ui.theme.StatusLine
import com.fitcompanion.poc.ui.theme.VoltButton
import kotlinx.coroutines.launch

private val fieldShape = RoundedCornerShape(16.dp)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Volt, unfocusedBorderColor = Palette.Outline, cursorColor = Palette.Volt)

@Composable
fun AiScreen(app: AppController, contentPadding: PaddingValues) {
    val scope = rememberCoroutineScope()
    val speaker = rememberSpeaker()
    var question by remember { mutableStateOf("") }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var showModels by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showShared by remember { mutableStateOf(false) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val notifyPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    // Ask once (Android 13+) so "Your coach replied" can appear when you're in another app.
    fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { app.fx.refresh() }

    if (showModels) ModelPicker(app) { showModels = false }
    if (showHistory) ChatHistory(app) { showHistory = false }
    if (showAccount) AccountSheet(app, onModels = { showAccount = false; showModels = true }) { showAccount = false }
    androidx.compose.runtime.LaunchedEffect(app.hasKey) { if (app.hasKey) app.loadModels() }

    fun send(q: String) {
        val text = q.trim(); if (text.isBlank() || app.coachBusy || !app.hasKey) return
        voiceNote = null
        askNotifications()
        if (app.alwaysShareSummary) { question = ""; app.askInBackground(text) } else pending = text
    }

    // Item layout: 0 = intro/starters (only when empty), then one item per turn, then status items.
    val turns = app.chat
    val firstTurnIndex = if (turns.isEmpty()) 1 else 0
    // When a reply arrives, scroll so the START of it is at the top (not the end).
    androidx.compose.runtime.LaunchedEffect(turns.size) {
        if (turns.isEmpty()) return@LaunchedEffect
        val last = turns.lastIndex + firstTurnIndex
        list.animateScrollToItem(if (turns.last().fromUser) last else last)
    }
    androidx.compose.runtime.LaunchedEffect(pending) {
        if (pending != null) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }

    // Keep the ask bar above the keyboard when it's open, otherwise above the tabs.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottom = with(density) { androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density).toDp() }
    val bottom = maxOf(contentPadding.calculateBottomPadding(), imeBottom)

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding(), bottom = bottom)) {
        // Compact top bar: title, model, history, new chat, account.
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("COACH", style = MaterialTheme.typography.headlineMedium)
                if (app.hasKey) Text((app.currentModelInfo?.name ?: app.model.substringAfter('/')) + "  ▾", style = MaterialTheme.typography.labelMedium,
                    color = Palette.Volt, maxLines = 1, modifier = Modifier.clickable { showModels = true }.padding(vertical = 2.dp))
            }
            if (app.hasKey) {
                TopIcon(androidx.compose.material.icons.Icons.Rounded.History, "Chat history") { showHistory = true }
                TopIcon(androidx.compose.material.icons.Icons.Rounded.Add, "New chat", enabled = turns.isNotEmpty()) { app.newChat(); speaker.stop(); pending = null }
            }
            TopIcon(androidx.compose.material.icons.Icons.Rounded.ManageAccounts, "AI account") { showAccount = true }
        }

        if (!app.hasKey) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp)) { ConnectCard(app) }
        } else {
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (turns.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (app.activities.isEmpty()) StatusLine("Sync on the Today tab first so there's data to talk about.", Palette.Warn)
                    SportCard(brush = Palette.VoltGlow) {
                        Text("G'DAY! WHAT DO YOU WANT TO KNOW?", style = MaterialTheme.typography.headlineSmall, color = Palette.Bg)
                        Text("Ask about your week, your challenges, sleep or recovery. The app works out every number; the coach talks you through them.",
                            style = MaterialTheme.typography.bodyMedium, color = Palette.Bg)
                    }
                    listOf("How did my week go?", "Am I walking more than last week?", "How am I tracking on my challenges?", "How's my recovery looking?")
                        .forEach { q -> SuggestionPill(q, enabled = !app.coachBusy) { send(q) } }
                    if (app.conversations.isNotEmpty()) Text("Earlier chats are under the clock icon at the top.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
            }
            items(turns.size) { i ->
                val t = turns[i]
                if (t.fromUser) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(t.text, style = MaterialTheme.typography.bodyLarge, color = Palette.Bg,
                            modifier = Modifier.padding(start = 48.dp).clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)).background(Palette.Volt).padding(14.dp))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)).background(Palette.Surface).padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Volt, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Eyebrow("Coach", Palette.Volt, Modifier.weight(1f))
                                Text(if (speaker.speaking) "STOP" else "READ ALOUD", style = MaterialTheme.typography.labelMedium, color = Palette.Volt,
                                    modifier = Modifier.clickable { if (speaker.speaking) speaker.stop() else speaker.speak(t.text.replace("*", "")) }.padding(4.dp))
                            }
                            Spacer(Modifier.size(8.dp))
                            MarkdownText(t.text)
                            Spacer(Modifier.size(8.dp))
                            Text("AI · ${t.model?.substringAfter('/') ?: ""}" + (t.costUsd?.let { " · cost ${centsText(it)} US" } ?: "") + " · not medical advice",
                                style = MaterialTheme.typography.labelSmall, color = Palette.TextDim, maxLines = 2)
                            if (t.sources.isNotEmpty()) SourcesLine(t.sources)
                            if (t.tripId != null && app.trip?.id == t.tripId) {
                                Spacer(Modifier.size(8.dp))
                                VoltButton("See the route and numbers", onClick = { app.showTrip = true })
                            }
                        }
                        if (t.cutShort) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("This answer was cut short.", style = MaterialTheme.typography.bodySmall, color = Palette.Warn, modifier = Modifier.weight(1f))
                            if (i == turns.lastIndex && !app.coachBusy) GhostButton("Continue", onClick = { send("Please continue where you left off.") })
                        }
                        if (i == turns.lastIndex && t.followUps.isNotEmpty() && !app.coachBusy && pending == null)
                            t.followUps.forEach { q -> SuggestionPill(q, enabled = true) { send(q) } }
                    }
                }
            }
            if (app.coachBusy) item { LiveAnswer(app) }
            if (!app.coachBusy && turns.lastOrNull()?.fromUser == true) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("No answer yet for that question.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.weight(1f))
                    VoltButton("Retry", onClick = { app.retryLast() })
                }
            }
            app.coachError?.let { m -> if (!app.coachBusy) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusLine(m, Palette.Warn)
                    if (m.contains("can't be used for chat")) VoltButton("Choose another model", icon = Icons.Rounded.AutoAwesome, onClick = { showModels = true })
                }
            } }
            pending?.let { q -> item {
                SportCard(padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = Palette.Volt, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Eyebrow("Share a summary with the AI?", Palette.Volt)
                    }
                    Text("To answer \"$q\", the coach needs a summary of your numbers: weekly distances, steps, sleep, resting heart rate, HRV and challenge progress. " +
                        "No GPS routes, photos, notes, name or account details. It goes to OpenRouter and the model you picked.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    Text(if (showShared) "Hide the summary" else "See exactly what's shared", style = MaterialTheme.typography.labelLarge, color = Palette.Volt,
                        modifier = Modifier.clickable { showShared = !showShared }.padding(vertical = 4.dp))
                    if (showShared) SharedPreview(app.currentChatFacts())
                    VoltButton("Always share and send", icon = Icons.Rounded.Send, modifier = Modifier.fillMaxWidth(), onClick = {
                        app.setAlwaysShare(true); pending = null; question = ""; showShared = false; app.askInBackground(q)
                    })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("Just this once", onClick = { pending = null; question = ""; showShared = false; app.askInBackground(q) }, modifier = Modifier.weight(1f))
                        GhostButton("Cancel", onClick = { pending = null }, modifier = Modifier.weight(1f))
                    }
                }
            } }
        }

        // Fixed ask bar.
        Column(Modifier.fillMaxWidth().background(Palette.Bg).padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp)) {
            voiceNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Warn) }
            OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), minLines = 1, maxLines = 4,
                placeholder = { Text(if (app.coachBusy) "Coach is answering… (you can leave this screen)" else if (turns.isEmpty()) "Ask anything, or tap the mic…" else "Ask a follow-up…", color = Palette.TextDim) },
                colors = fieldColors(), shape = RoundedCornerShape(28.dp),
                trailingIcon = {
                    if (app.coachBusy) androidx.compose.material3.IconButton(onClick = { app.cancelAnswer() }) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.Stop, "Stop", tint = Palette.Text)
                        }
                    }
                    else if (question.isBlank()) MicButton("Ask your coach", onText = { send(it) },
                        onUnavailable = { voiceNote = "Voice input isn't available on this phone." })
                    else androidx.compose.material3.IconButton(onClick = { send(question) }, enabled = !app.coachBusy) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.Volt), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Send, "Send", tint = Palette.Bg)
                        }
                    }
                })
        }
        }
    }
}

@Composable
private fun TopIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, label, tint = if (enabled) Palette.Text else Palette.TextDim.copy(alpha = 0.4f))
    }
}

/** Saved chats; tap to reopen, or delete. */
@Composable
private fun ChatHistory(app: AppController, onClose: () -> Unit) {
    var confirmAll by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Palette.Bg).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CHAT HISTORY", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Palette.Text) }
            }
            Text("Saved only on this phone. Your newest 50 chats are kept.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            if (app.conversations.isEmpty()) Text("No saved chats yet.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(app.conversations.size) { i ->
                    val c = app.conversations[i]
                    val current = c.id == app.currentConversation?.id
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (current) Palette.Volt.copy(alpha = 0.15f) else Palette.Surface)
                        .clickable { app.openConversation(c.id); onClose() }.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(java.time.Instant.ofEpochMilli(c.startedAt).atZone(app.zone).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")) +
                                " · ${c.turns.count { !it.fromUser }} answers", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                        }
                        androidx.compose.material3.IconButton(onClick = { app.deleteConversation(c.id) }) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.Delete, "Delete chat", tint = Palette.TextDim)
                        }
                    }
                }
            }
            if (app.conversations.isNotEmpty()) GhostButton(if (confirmAll) "Tap again to delete all chats" else "Delete all chats", color = Palette.Heart,
                onClick = { if (confirmAll) { app.deleteAllConversations(); confirmAll = false } else confirmAll = true })
        }
    }
}

/** AI account: model, sharing choice, sign out. */
@Composable
private fun AccountSheet(app: AppController, onModels: () -> Unit, onClose: () -> Unit) {
    var showShared by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Palette.Bg).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI ACCOUNT", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Palette.Text) }
            }
            if (!app.hasKey) ConnectCard(app) else {
            StatusLine(app.signInMessage ?: "Connected to OpenRouter", Palette.Good)
            SportCard(padding = 16.dp) {
                Eyebrow("Model")
                Text(app.currentModelInfo?.name ?: app.model, style = MaterialTheme.typography.titleMedium)
                app.currentModelInfo?.let { Text(costLabel(app, it), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
                GhostButton("Choose AI model", icon = Icons.Rounded.AutoAwesome, onClick = onModels)
            }
            SportCard(padding = 16.dp) {
                Eyebrow("What's shared")
                Text(if (app.alwaysShareSummary) "A summary of your numbers is shared with each question (no GPS, photos, notes or name)."
                    else "You're asked before anything is shared.", style = MaterialTheme.typography.bodyMedium)
                Text(if (showShared) "Hide" else "See exactly what's shared", style = MaterialTheme.typography.labelLarge, color = Palette.Volt,
                    modifier = Modifier.clickable { showShared = !showShared }.padding(vertical = 4.dp))
                if (showShared) SharedPreview(app.currentChatFacts())
                if (app.alwaysShareSummary) GhostButton("Ask me before sharing", onClick = { app.setAlwaysShare(false) })
            }
            SportCard(padding = 16.dp) {
                Eyebrow("Sign out")
                Text("Signing out removes the key from this phone. To cancel it completely, delete \"Fitness Companion (Android)\" at openrouter.ai → Keys.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                GhostButton("Sign out of OpenRouter", color = Palette.Heart, onClick = { app.removeKey() })
            }
            }
        }
    }
}

@Composable
private fun ConnectCard(app: AppController) {
    var keyInput by remember { mutableStateOf("") }
    var pasteKey by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val openBrowser: (android.net.Uri) -> Unit = { uri ->
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
    }
    SportCard(padding = 16.dp) {
        Text("CONNECT THE AI COACH", style = MaterialTheme.typography.titleLarge)
        Text("Sign in with your free OpenRouter account. Nothing to copy: you approve once in the browser and you're done. " +
            "You pay OpenRouter directly for what you use, usually a few cents a question, and can set a spending limit there.",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        if (app.signingIn) {
            StatusLine(app.signInMessage ?: "Waiting for OpenRouter…", Palette.Volt)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Open sign-in again", onClick = { app.signInWithOpenRouter(openBrowser) })
                GhostButton("Cancel", onClick = { app.cancelSignIn() })
            }
        } else {
            VoltButton("Sign in with OpenRouter", icon = Icons.Rounded.Key, modifier = Modifier.fillMaxWidth(),
                onClick = { app.signInWithOpenRouter(openBrowser) })
            app.signInMessage?.let { StatusLine(it, Palette.Warn) }
        }
        Text("No account yet? The sign-in page lets you create one.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        Text(if (pasteKey) "Hide" else "Have a key already? Paste it instead", style = MaterialTheme.typography.labelLarge, color = Palette.Volt,
            modifier = Modifier.clickable { pasteKey = !pasteKey }.padding(vertical = 6.dp))
        if (pasteKey) {
            OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Rounded.Key, null) }, colors = fieldColors(), shape = fieldShape)
            VoltButton("Save key", onClick = { app.saveAiSettings(keyInput, app.model); keyInput = "" }, enabled = keyInput.isNotBlank())
        }
    }
}

@Composable
private fun SuggestionPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Palette.SurfaceHigh).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Volt, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Palette.Text else Palette.TextDim)
    }
}

@Composable
private fun SharedPreview(facts: String) {
    Text(facts, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Palette.Text,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Bg).padding(12.dp))
}

/** US cents with sensible precision: 0.042¢, 0.42¢, 4.2¢, 42¢. */
fun centsText(usd: Double): String {
    val c = usd * 100
    return when {
        c <= 0.0 -> "free"
        c < 0.01 -> "<0.01¢"
        c < 0.1 -> "%.3f¢".format(c)
        c < 1 -> "%.2f¢".format(c)
        c < 10 -> "%.1f¢".format(c)
        else -> "%.0f¢".format(c)
    }
}

fun costLabel(app: AppController, m: com.fitcompanion.poc.ai.OpenRouterClient.ModelInfo): String =
    if (m.free) "Free model" else "About ${centsText(m.usdPerQuestion(app.avgTokensIn.toInt(), app.avgTokensOut.toInt()))} US per question"

/** 100 questions a week for a month, in A$ (about 433 questions). */
fun monthlyAud(app: AppController, m: com.fitcompanion.poc.ai.OpenRouterClient.ModelInfo): Double =
    m.usdPerQuestion(app.avgTokensIn.toInt(), app.avgTokensOut.toInt()) * 100 * 52 / 12 * app.fx.audPerUsd

const val PREMIUM_AUD_MONTH = 14.99   // Google Health Premium, Australia (Google, May 2026)

@Composable
private fun LiveAnswer(app: AppController) {
    var showThinking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)).background(Palette.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Volt, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Eyebrow("Coach is working", Palette.Volt, Modifier.weight(1f))
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Volt, strokeWidth = 2.dp)
        }
        app.steps.forEach { st ->
            Row(verticalAlignment = Alignment.Top) {
                Text(if (st.done) "✓" else "•", color = if (st.done) Palette.Good else Palette.Volt, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(18.dp))
                Text(st.text, style = MaterialTheme.typography.bodySmall, color = if (st.done) Palette.TextDim else Palette.Text)
            }
        }
        if (app.liveReasoning.isNotBlank()) {
            Text(if (showThinking) "Hide its thinking" else "See its thinking", style = MaterialTheme.typography.labelMedium, color = Palette.Volt,
                modifier = Modifier.clickable { showThinking = !showThinking }.padding(vertical = 2.dp))
            if (showThinking) Text(app.liveReasoning.takeLast(1200).replace("*", ""), style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim, fontFamily = FontFamily.Monospace)
        }
        if (app.liveText.isNotBlank()) { androidx.compose.material3.HorizontalDivider(color = Palette.Outline); MarkdownText(app.liveText) }
        Text("You can leave this screen or the app; you'll get a notification when it's done.",
            style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
    }
}

@Composable
private fun SourcesLine(sources: List<String>) {
    var open by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Text(if (open) "Hide research" else "Research it drew on (${sources.size})", style = MaterialTheme.typography.labelMedium, color = Palette.Volt,
        modifier = Modifier.clickable { open = !open }.padding(top = 6.dp, bottom = 2.dp))
    if (open) sources.forEach { s ->
        val title = s.substringBefore('|'); val url = s.substringAfter('|', "")
        Text("• $title", style = MaterialTheme.typography.bodySmall, color = if (url.isNotBlank()) Palette.Text else Palette.TextDim,
            modifier = Modifier.clickable(enabled = url.isNotBlank()) {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            }.padding(vertical = 2.dp))
    }
}

/** Searchable list of OpenRouter's current models with a rough cost per coach question. */
@Composable
private fun ModelPicker(app: AppController, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Popular") }
    androidx.compose.runtime.LaunchedEffect(Unit) { app.loadModels() }
    val popular = listOf("openai/", "anthropic/", "google/", "meta-llama/", "mistralai/", "deepseek/", "x-ai/", "qwen/")
    val list = app.models.filter { m ->
        (query.isBlank() || m.name.contains(query, true) || m.id.contains(query, true)) && when (filter) {
            "Free" -> m.free
            "Cheap" -> !m.free && m.usdPerQuestion(app.avgTokensIn.toInt(), app.avgTokensOut.toInt()) < 0.01
            "Popular" -> popular.any { m.id.startsWith(it) } && !m.free
            else -> true
        }
    }.sortedWith(compareByDescending<com.fitcompanion.poc.ai.OpenRouterClient.ModelInfo> { it.id == app.model }.thenBy { it.usdPerQuestion(app.avgTokensIn.toInt(), app.avgTokensOut.toInt()) })
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Palette.Bg).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CHOOSE AI MODEL", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = onClose) { Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Close", tint = Palette.Text) }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search, e.g. claude, gemini, gpt", color = Palette.TextDim) }, colors = fieldColors(), shape = fieldShape)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Popular", "Cheap", "Free", "All").forEach { f -> ChoiceChip(f, filter == f, { filter = f }) }
            }
            Text(when (filter) {
                "Free" -> "Free models cost nothing but can be slow or busy, and some providers may use what you send to improve their models."
                else -> "Cost per question (/q) is estimated from " + (if (app.answersCount >= 3) "your own average question (${app.avgTokensIn} tokens in, ${app.avgTokensOut} out)" else "a typical question (~4,500 tokens in, ~700 out)") +
                    ", plus extra for models that think first. *A\$/mo = 100 questions a week. Google Health Premium is A\$${"%.2f".format(PREMIUM_AUD_MONTH)}/month."
            }, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            app.currentModelInfo?.takeIf { !it.free }?.let { cur ->
                val aud = monthlyAud(app, cur)
                SportCard(padding = 12.dp) {
                    Eyebrow("Your model vs Google Health Premium")
                    Text("At 100 questions a week, ${cur.name} costs about A$%.2f a month, ".format(aud) +
                        if (aud < PREMIUM_AUD_MONTH) "around A$%.2f (%.0f%%) less than Premium's A$%.2f.".format(PREMIUM_AUD_MONTH - aud, (1 - aud / PREMIUM_AUD_MONTH) * 100, PREMIUM_AUD_MONTH)
                        else "about A$%.2f more than Premium's A$%.2f.".format(aud - PREMIUM_AUD_MONTH, PREMIUM_AUD_MONTH),
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Exchange rate US\$1 = A\$%.2f%s. Premium also includes features this app doesn't have.".format(app.fx.audPerUsd, if (app.fx.isLive) "" else " (approx.)") +
                        (if (app.answersCount > 0) " You've spent about ${centsText(app.spentUsd)} US over ${app.answersCount} answers so far." else ""),
                        style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                }
            }
            app.modelsMessage?.let { StatusLine(it, Palette.Warn) }
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list.size) { i ->
                    val m = list[i]
                    val selected = m.id == app.model
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (selected) Palette.Volt.copy(alpha = 0.15f) else Palette.Surface)
                        .clickable { app.chooseModel(m.id); onClose() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(m.id, style = MaterialTheme.typography.labelSmall, color = Palette.TextDim, maxLines = 1)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (m.free) "FREE" else centsText(m.usdPerQuestion(app.avgTokensIn.toInt(), app.avgTokensOut.toInt())) + "/q",
                                style = MaterialTheme.typography.titleSmall, color = if (m.free) Palette.Good else Palette.Text)
                            if (!m.free) Text("A$%.2f/mo*".format(monthlyAud(app, m)), style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                            if (selected) Text("✓ IN USE", style = MaterialTheme.typography.labelSmall, color = Palette.Volt)
                        }
                    }
                }
            }
        }
    }
}
