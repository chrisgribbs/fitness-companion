package com.fitcompanion.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
    var keyInput by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val speaker = rememberSpeaker()
    var voiceNote by remember { mutableStateOf<String?>(null) }

    var pending by remember { mutableStateOf<String?>(null) }
    var showModels by remember { mutableStateOf(false) }
    var showShared by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    if (showModels) ModelPicker(app) { showModels = false }
    androidx.compose.runtime.LaunchedEffect(app.chat.size, app.busy) { scroll.animateScrollTo(scroll.maxValue) }
    androidx.compose.runtime.LaunchedEffect(app.hasKey) { if (app.hasKey) app.loadModels() }

    fun send(q: String) {
        val text = q.trim(); if (text.isBlank() || app.busy) return
        if (!app.hasKey) { pending = null; return }
        if (app.alwaysShareSummary) { question = ""; scope.launch { app.ask(text) } } else pending = text
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll)
            .padding(start = 18.dp, end = 18.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Coach", subtitle = "Chat about your walks, runs and recovery")

        // Which AI is answering, and what it roughly costs. Tap to change.
        if (app.hasKey) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface).clickable { showModels = true }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Volt, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(app.currentModelInfo?.name ?: app.model, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(app.currentModelInfo?.let { costLabel(it) } ?: "AI model", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
            }
            Text("CHANGE", style = MaterialTheme.typography.labelLarge, color = Palette.Volt)
        }

        if (app.activities.isEmpty()) StatusLine("Sync on the Today tab first so there's data to talk about.", Palette.Warn)

        if (app.chat.isEmpty()) {
            SportCard(brush = Palette.VoltGlow) {
                Text("G'DAY! WHAT DO YOU WANT TO KNOW?", style = MaterialTheme.typography.headlineSmall, color = Palette.Bg)
                Text("Ask about your week, your challenges, sleep or recovery. Tap a question or use the mic. The app works out every number; the coach just talks you through them.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.Bg)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("How did my week go?", "Am I walking more than last week?", "How am I tracking on my challenges?", "How's my recovery looking?")
                    .forEach { q -> SuggestionPill(q, enabled = app.hasKey && !app.busy) { send(q) } }
            }
        }

        // The conversation.
        app.chat.forEachIndexed { i, t ->
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
                            Eyebrow("Coach", Palette.Volt)
                        }
                        Spacer(Modifier.size(8.dp))
                        MarkdownText(t.text)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI · ${t.model?.substringAfter('/') ?: ""} · not medical advice", style = MaterialTheme.typography.labelSmall,
                            color = Palette.TextDim, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(if (speaker.speaking) "STOP" else "READ ALOUD", style = MaterialTheme.typography.labelLarge, color = Palette.Volt,
                            modifier = Modifier.clickable { if (speaker.speaking) speaker.stop() else speaker.speak(t.text.replace("*", "")) }.padding(6.dp))
                    }
                    if (i == app.chat.lastIndex && t.followUps.isNotEmpty() && !app.busy)
                        t.followUps.forEach { q -> SuggestionPill(q, enabled = !app.busy) { send(q) } }
                }
            }
        }
        if (app.busy) StatusLine("Coach is thinking…", Palette.Volt)
        app.message?.let { if (!app.busy) StatusLine(it, Palette.Warn) }

        // First-time (or ask-every-time) sharing choice.
        pending?.let { q ->
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
                    app.setAlwaysShare(true); pending = null; question = ""; scope.launch { app.ask(q) }
                })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Just this once", onClick = { pending = null; question = ""; scope.launch { app.ask(q) } }, modifier = Modifier.weight(1f))
                    GhostButton("Cancel", onClick = { pending = null }, modifier = Modifier.weight(1f))
                }
            }
        }

        // Ask box.
        if (app.hasKey) {
            OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), minLines = 1, maxLines = 5,
                placeholder = { Text(if (app.chat.isEmpty()) "Ask anything, or tap the mic…" else "Ask a follow-up…", color = Palette.TextDim) },
                colors = fieldColors(), shape = fieldShape,
                trailingIcon = {
                    if (question.isBlank()) MicButton("Ask your coach", onText = { send(it); voiceNote = null },
                        onUnavailable = { voiceNote = "Voice input isn't available on this phone." })
                    else androidx.compose.material3.IconButton(onClick = { send(question) }, enabled = !app.busy) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.Volt), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Send, "Send", tint = Palette.Bg)
                        }
                    }
                })
            voiceNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Warn) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (app.alwaysShareSummary) "Sharing a summary of your numbers (no GPS, photos or notes)." else "You'll be asked before anything is shared.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextDim, modifier = Modifier.weight(1f))
                if (app.chat.isNotEmpty()) Text("NEW CHAT", style = MaterialTheme.typography.labelLarge, color = Palette.Volt,
                    modifier = Modifier.clickable { app.newChat(); speaker.stop() }.padding(6.dp))
            }
            if (app.alwaysShareSummary) Text(if (showShared) "Hide what's shared" else "See what's shared · stop auto-sharing", style = MaterialTheme.typography.labelSmall, color = Palette.Volt,
                modifier = Modifier.clickable { showShared = !showShared })
            if (app.alwaysShareSummary && showShared && pending == null) {
                SharedPreview(app.currentChatFacts())
                GhostButton("Ask me before sharing", onClick = { app.setAlwaysShare(false); showShared = false })
            }
        }

        SectionHeader("AI account", if (app.hasKey) (if (showSettings) "Hide" else "Settings") else null) { showSettings = !showSettings }
        val context = androidx.compose.ui.platform.LocalContext.current
        val openBrowser: (android.net.Uri) -> Unit = { uri ->
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
        }
        var pasteKey by remember { mutableStateOf(false) }
        if (!app.hasKey) {
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
        } else {
            StatusLine(app.signInMessage ?: "Connected to OpenRouter", Palette.Good)
            if (showSettings) {
                SportCard(padding = 16.dp) {
                    GhostButton("Choose AI model", icon = Icons.Rounded.AutoAwesome, onClick = { showModels = true })
                    Text("Signing out removes the key from this phone. To cancel it completely, delete \"Fitness Companion (Android)\" at openrouter.ai → Keys.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    GhostButton("Sign out of OpenRouter", color = Palette.Heart, onClick = { app.removeKey() })
                }
            }
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

fun costLabel(m: com.fitcompanion.poc.ai.OpenRouterClient.ModelInfo): String = when {
    m.free -> "Free model"
    m.centsPerQuestion < 0.1 -> "Under 0.1¢ per question"
    m.centsPerQuestion < 10 -> "About %.1f¢ (US) per question".format(m.centsPerQuestion)
    else -> "About %.0f¢ (US) per question".format(m.centsPerQuestion)
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
            "Cheap" -> !m.free && m.centsPerQuestion < 1
            "Popular" -> popular.any { m.id.startsWith(it) } && !m.free
            else -> true
        }
    }.sortedWith(compareByDescending<com.fitcompanion.poc.ai.OpenRouterClient.ModelInfo> { it.id == app.model }.thenBy { it.centsPerQuestion })
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
                else -> "Cost is a rough estimate for one coach question, charged by OpenRouter to your account."
            }, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
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
                            Text(if (m.free) "FREE" else if (m.centsPerQuestion < 0.1) "<0.1¢" else "%.1f¢".format(m.centsPerQuestion),
                                style = MaterialTheme.typography.titleSmall, color = if (m.free) Palette.Good else Palette.Text)
                            if (selected) Text("✓ IN USE", style = MaterialTheme.typography.labelSmall, color = Palette.Volt)
                        }
                    }
                }
            }
        }
    }
}
