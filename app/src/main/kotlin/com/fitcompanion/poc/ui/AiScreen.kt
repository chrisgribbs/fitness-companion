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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
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
    var modelInput by remember { mutableStateOf(app.model) }
    var question by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var asked by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(!app.hasKey) }
    val speaker = rememberSpeaker()
    var voiceNote by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Coach", subtitle = "AI insights on your data")

        SportCard(brush = Palette.VoltGlow) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Bg, modifier = Modifier.size(30.dp))
            Text("ASK ANYTHING ABOUT YOUR TRAINING", style = MaterialTheme.typography.headlineMedium, color = Palette.Bg)
            Text("The app works out every number itself. The AI only explains them — and you see exactly what's sent before it goes.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Bg)
        }

        if (app.activities.isEmpty()) StatusLine("Sync on the Today tab first so there's data to analyse.", Palette.Warn)

        asked?.let { q ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(q, style = MaterialTheme.typography.bodyMedium, color = Palette.Bg,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)).background(Palette.Volt).padding(14.dp))
            }
        }
        if (app.busy && asked != null) StatusLine("Coach is thinking…", Palette.Volt)
        app.answer?.let { a ->
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Volt, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(a, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)).background(Palette.Surface).padding(16.dp))
                    GhostButton(if (speaker.speaking) "Stop reading" else "Read aloud", icon = Icons.Rounded.VolumeUp,
                        onClick = { if (speaker.speaking) speaker.stop() else speaker.speak(a) })
                }
            }
        }

        SectionHeader("Ask")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Summarise my week", "Is my running increasing consistently?", "How much did I climb this month?", "Compare my HRV with my usual range", "How's my resting heart rate trending?")
                .forEach { q -> ChoiceChip(q, question == q, { question = q; preview = null }) }
        }
        OutlinedTextField(question, { question = it; preview = null }, Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text("Type or tap the mic and ask…", color = Palette.TextDim) }, colors = fieldColors(), shape = fieldShape,
            trailingIcon = {
                MicButton("Ask your coach", onText = { question = it; preview = null; voiceNote = null },
                    onUnavailable = { voiceNote = "Voice input isn't available on this phone." })
            })
        voiceNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Warn) }

        val shown = preview
        if (shown == null) {
            GhostButton("Preview what will be sent", icon = Icons.Rounded.Visibility, onClick = { preview = app.factsSheet() }, enabled = question.isNotBlank())
        } else {
            SportCard(padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = Palette.Volt, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Eyebrow("Exactly what goes to OpenRouter", Palette.Volt)
                }
                Text("Your question plus this summary. No GPS routes, raw heart-rate data, photos, notes, name or account details.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                Text(shown, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Palette.Text,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Bg).padding(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VoltButton("Send", icon = Icons.Rounded.Send, enabled = app.hasKey && !app.busy, onClick = {
                        val q = question
                        asked = q; preview = null
                        scope.launch { app.ask(q, shown) }
                    })
                    GhostButton("Cancel", onClick = { preview = null })
                }
                if (!app.hasKey) Text("Add your OpenRouter key below first.", style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
            }
        }
        app.message?.let { if (!app.busy) StatusLine(it, Palette.Warn) }

        SectionHeader("OpenRouter", if (showSettings) "Hide" else "Edit") { showSettings = !showSettings }
        StatusLine(if (app.hasKey) "Key saved, encrypted on this phone · model ${app.model}" else "No key yet", if (app.hasKey) Palette.Good else Palette.Warn)
        if (showSettings) {
            SportCard(padding = 16.dp) {
                Text("Create a key at openrouter.ai → Keys (set a monthly limit), then paste it here yourself.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (app.hasKey) "Replace key" else "API key") }, visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Rounded.Key, null) }, colors = fieldColors(), shape = fieldShape)
                OutlinedTextField(modelInput, { modelInput = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Model id (from openrouter.ai/models)") }, colors = fieldColors(), shape = fieldShape)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VoltButton("Save", onClick = { app.saveAiSettings(keyInput, modelInput); keyInput = ""; showSettings = false })
                    if (app.hasKey) GhostButton("Remove key", color = Palette.Heart, onClick = { app.removeKey() })
                }
            }
        }
    }
}
