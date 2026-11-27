package com.fitcompanion.poc.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitcompanion.poc.ui.theme.Palette
import java.util.Locale
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.VoltButton

/**
 * Tap to speak instead of typing. Uses Android's built-in speech recogniser (it may use Google's
 * speech service, depending on your phone). Only the recognised text comes back into the app.
 */
@Composable
fun MicButton(prompt: String, onText: (String) -> Unit, onUnavailable: () -> Unit = {}) {
    val listen = rememberVoiceInput(prompt, onText, onUnavailable)
    IconButton(onClick = listen) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.Volt), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Mic, "Speak", tint = Palette.Bg)
        }
    }
}

/**
 * Returns a function that starts listening. Google's one-shot voice box stops at the first pause, so instead the app
 * keeps the phone's speech recogniser going (restarting it after each pause) until you tap Done, showing the words
 * as they're recognised. It stops by itself after about 20 seconds of silence. Speech is recognised by the phone's
 * speech service (usually Google's); only the text comes back into the app. Needs the microphone permission;
 * if you say no, it falls back to Google's standard voice box.
 */
@Composable
fun rememberVoiceInput(prompt: String, onText: (String) -> Unit, onUnavailable: () -> Unit = {}): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onText)
    }
    fun oneShot() {
        try {
            launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt))
        } catch (_: ActivityNotFoundException) { onUnavailable() }
    }
    val latestOnText = rememberUpdatedState(onText)
    val session = remember { VoiceSession(ctx) { latestOnText.value(it) } }
    DisposableEffect(Unit) { onDispose { session.cancel() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) session.start() else oneShot()
    }
    if (session.listening) VoiceDialog(prompt, session)
    return {
        when {
            !SpeechRecognizer.isRecognitionAvailable(ctx) -> oneShot()
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> session.start()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** Keeps the recogniser running across pauses and collects the text until stopped. */
class VoiceSession(private val ctx: Context, private val onText: (String) -> Unit) {
    var listening by mutableStateOf(false); private set
    /** Everything recognised so far, plus the words currently being heard. */
    var shown by mutableStateOf(""); private set
    var hearing by mutableStateOf(false); private set
    private var rec: SpeechRecognizer? = null
    private val said = StringBuilder()
    private var silentTries = 0
    private var finished = true
    private val main = Handler(Looper.getMainLooper())

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Ask for longer pauses before it decides you've finished (some phones ignore these; the restart covers that).
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)

    private fun append(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isNotEmpty()) { if (said.isNotEmpty()) said.append(' '); said.append(t) }
        shown = said.toString()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { hearing = true }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { hearing = false }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partial: Bundle?) {
            val p = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            shown = listOf(said.toString(), p).filter { it.isNotBlank() }.joinToString(" ")
        }
        override fun onResults(results: Bundle?) {
            append(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            silentTries = 0
            if (listening) listenAgain() else finish()
        }
        override fun onError(error: Int) {
            hearing = false
            if (!listening) { finish(); return }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // A pause with nothing said. Keep going, but stop after ~20 s of silence in a row.
                    if (++silentTries >= 4) { listening = false; finish() } else listenAgain()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> main.postDelayed({ if (listening) listenAgain() }, 300)
                else -> { listening = false; finish() }
            }
        }
    }

    private fun listenAgain() { runCatching { rec?.startListening(intent()) }.onFailure { listening = false; finish() } }

    fun start() {
        if (listening) return
        said.clear(); shown = ""; silentTries = 0; finished = false
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(ctx).apply { setRecognitionListener(listener) }
        listening = true
        listenAgain()
    }

    /** Done: keep what was said (including the words still being processed). */
    fun stop() {
        if (!listening) return
        listening = false
        runCatching { rec?.stopListening() }
        // If the recogniser doesn't come back with the last words, finish anyway with the on-screen text.
        main.postDelayed({ if (!finished) { if (shown.length > said.length) { said.clear(); said.append(shown) }; finish() } }, 2000)
    }

    fun cancel() { listening = false; said.clear(); shown = ""; finish() }

    private fun finish() {
        if (finished) return
        finished = true; listening = false; hearing = false
        runCatching { rec?.destroy() }; rec = null
        val text = said.toString().trim()
        if (text.isNotEmpty()) onText(text)
    }
}

@Composable
private fun VoiceDialog(prompt: String, s: VoiceSession) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.Surface).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (s.hearing) Palette.Heart else Palette.TextDim))
                Spacer(Modifier.width(10.dp))
                Text(if (s.hearing) "Listening…" else "Listening — take your time", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            }
            Text(s.shown.ifBlank { prompt }, style = MaterialTheme.typography.bodyLarge,
                color = if (s.shown.isBlank()) Palette.TextDim else Palette.Text,
                modifier = Modifier.heightIn(min = 80.dp, max = 260.dp).verticalScroll(rememberScrollState()))
            Text("Pauses are fine. Tap Done when you've finished.", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Cancel", onClick = { s.cancel() }, modifier = Modifier.weight(1f))
                VoltButton("Done", onClick = { s.stop() }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Reads text aloud with the phone's own text-to-speech voice (on the phone, nothing is sent). */
class Speaker(context: android.content.Context) {
    private var ready = false
    var speaking by mutableStateOf(false); private set
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) runCatching { tts.setLanguage(Locale.getDefault()) }
        }
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true }
            override fun onDone(id: String?) { speaking = false }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking = false }
        })
    }

    fun speak(text: String) {
        if (!ready) return
        // Drop the AI footer and markdown symbols so it sounds natural.
        val clean = text.substringBefore("\n\n— AI-generated").replace(Regex("[*#_`]"), "")
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "coach")
        speaking = true
    }

    fun stop() { tts.stop(); speaking = false }
    fun shutdown() = tts.shutdown()
}

@Composable
fun rememberSpeaker(): Speaker {
    val ctx = LocalContext.current
    val speaker = remember { Speaker(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    return speaker
}
