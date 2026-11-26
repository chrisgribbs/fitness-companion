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

/**
 * Tap to speak instead of typing. Uses Android's built-in speech recogniser (it may use Google's
 * speech service, depending on your phone). Only the recognised text comes back into the app.
 */
@Composable
fun MicButton(prompt: String, onText: (String) -> Unit, onUnavailable: () -> Unit = {}) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
    }
    IconButton(onClick = {
        try {
            launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt))
        } catch (_: ActivityNotFoundException) { onUnavailable() }
    }) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.Volt), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Mic, "Speak", tint = Palette.Bg)
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
