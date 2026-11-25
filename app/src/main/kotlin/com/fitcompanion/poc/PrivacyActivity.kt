package com.fitcompanion.poc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown by Health Connect when you tap "privacy policy" for this app. */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.padding(24.dp)) {
                    Text("How this app uses your health data", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "This is a private app for one person. It only reads data (it never writes to Health Connect). " +
                            "Health readings are kept in memory while the app is open; only your challenge settings, an " +
                            "encrypted OpenRouter key and a cache of place-name lookups are saved on the phone. " +
                            "Nothing is sent anywhere automatically. If you choose to use AI, the app first shows you the exact " +
                            "summary it will send to OpenRouter. Designing a challenge sends only your own description; checking " +
                            "places sends only place names to OpenStreetMap. The optional Google Health API test talks only to Google. " +
                            "You can revoke access in Health Connect settings, and delete all local data from the AI tab.",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
