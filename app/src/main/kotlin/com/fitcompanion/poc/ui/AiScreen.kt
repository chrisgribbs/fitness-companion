package com.fitcompanion.poc.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fitcompanion.poc.AppController
import kotlinx.coroutines.launch

@Composable
fun AiScreen(app: AppController) {
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf(app.model) }
    var question by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI analysis", style = MaterialTheme.typography.headlineSmall)
        Text("Uses OpenRouter with your own API key. The app works out every number itself; the AI only explains them. " +
            "Nothing is sent until you've seen it and tapped Send.", style = MaterialTheme.typography.bodyMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OpenRouter settings", style = MaterialTheme.typography.titleMedium)
                Text(if (app.hasKey) "Key saved (encrypted on this phone)." else "No key yet. Create one at openrouter.ai → Keys, then paste it here yourself.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (app.hasKey) "Replace key" else "API key") }, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(modelInput, { modelInput = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Model id (any from openrouter.ai/models)") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.saveAiSettings(keyInput, modelInput); keyInput = "" }) { Text("Save") }
                    if (app.hasKey) OutlinedButton(onClick = { app.removeKey() }) { Text("Remove key") }
                }
                Text("Tip: set a monthly spending limit on the key in your OpenRouter account.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Ask about your data", style = MaterialTheme.typography.titleMedium)
        if (app.activities.isEmpty()) Text("Refresh on the Challenges tab first so there's data to analyse.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Summarise my week", "Is my running increasing consistently?", "Compare my HRV with my usual range")
                .forEach { q -> FilterChip(selected = question == q, onClick = { question = q }, label = { Text(q) }) }
        }
        OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Your question") })
        OutlinedButton(onClick = { preview = app.factsSheet() }, enabled = question.isNotBlank()) { Text("Preview what will be sent") }

        preview?.let { facts ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This exact text goes to OpenRouter and the model provider you chose, plus your question. " +
                        "No GPS routes, no raw heart-rate data, no name or account details.", style = MaterialTheme.typography.bodySmall)
                    Text(facts, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { app.ask(question, facts) } }, enabled = app.hasKey && !app.busy) { Text("Send") }
                        TextButton(onClick = { preview = null }) { Text("Cancel") }
                    }
                }
            }
        }

        app.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        app.answer?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(16.dp)) } }

        Text("Your data", style = MaterialTheme.typography.titleMedium)
        Text("Stored on this phone: your challenges, the encrypted OpenRouter key, and a cache of place lookups. " +
            "Activity data is re-read from Health Connect and only kept in memory.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { if (confirmWipe) { app.deleteAllLocalData(); confirmWipe = false } else confirmWipe = true }) {
            Text(if (confirmWipe) "Tap again: delete challenges, key and cache" else "Delete all local data")
        }
    }
}
