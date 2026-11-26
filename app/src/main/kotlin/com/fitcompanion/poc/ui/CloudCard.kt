package com.fitcompanion.poc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.SportCard
import com.fitcompanion.poc.ui.theme.VoltButton
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private const val PACKAGE = "com.fitcompanion.poc"
private const val SHA1 = "69:CE:07:EE:12:92:C7:22:4D:7E:1F:44:AB:D7:5E:36:28:70:71:83"

/** Connect straight to your Google Health account, with the one-off Google setup steps built in. */
@Composable
fun CloudCard(app: AppController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showSteps by remember { mutableStateOf(!app.cloudEnabled) }
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun copy(label: String, text: String) =
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))

    SportCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Cloud, null, tint = Palette.Sleep)
            Spacer(Modifier.width(8.dp)); Eyebrow("Recommended", Palette.Sleep)
        }
        Text("GOOGLE HEALTH ACCOUNT", style = MaterialTheme.typography.headlineSmall)
        Text("Reads your workouts, steps, sleep and heart data straight from your Google Health account — exactly what the Google Health app shows. " +
            "Doesn't rely on Health Connect sharing. Read-only.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)

        val c = app.cloudStatus
        if (app.cloudEnabled) {
            if (c?.lastRead != null) {
                Text("✓ Last read ${c.lastRead.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a"))} · ${c.workouts} workouts" +
                    (c.newestWorkout?.let { " · newest ${it.format(DateTimeFormatter.ofPattern("EEE d MMM h:mm a"))}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Palette.Good)
            }
            c?.problems?.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = Palette.Warn) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c?.needsSignIn == true || c == null) VoltButton("Sign in again", onClick = { scope.launch { app.signInCloudAgain() } })
                else VoltButton("Sync now", onClick = { scope.launch { app.refresh() } }, enabled = !app.busy)
                GhostButton("Disconnect", color = Palette.Heart, onClick = { app.disconnectCloud() })
            }
        } else {
            VoltButton("Connect Google Health", icon = Icons.Rounded.Cloud, enabled = !app.busy, onClick = { scope.launch { app.connectCloud() } })
            Text("Do the one-off Google setup below first (about 15 minutes, once). Connect won't work until it's done.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
        }
        app.message?.takeIf { it.contains("Google", ignoreCase = true) }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Warn) }

        Text(if (showSteps) "Hide setup steps ▲" else "Show setup steps ▼", style = MaterialTheme.typography.labelLarge, color = Palette.Volt,
            modifier = Modifier.clickable { showSteps = !showSteps }.padding(vertical = 4.dp))

        if (showSteps) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Step(1, "Open Google Cloud and sign in with the same Google account as your Fitbit. Create a project called \"fitness-personal\".")
                GhostButton("Open Google Cloud", icon = Icons.Rounded.OpenInNew, onClick = { open("https://console.cloud.google.com/projectcreate") })
                Step(2, "Turn on the Google Health API: search for \"Google Health API\" and tap Enable.")
                GhostButton("Open API library", icon = Icons.Rounded.OpenInNew, onClick = { open("https://console.cloud.google.com/apis/library") })
                Step(3, "Google Auth Platform → Get started. App name: Fitness Companion. Support email: yours. Audience: External. Contact: your email. Agree and Create.")
                GhostButton("Open Google Auth Platform", icon = Icons.Rounded.OpenInNew, onClick = { open("https://console.cloud.google.com/auth/overview") })
                Step(4, "Audience → Test users → Add users → your Gmail address. Leave Publishing status on \"Testing\".")
                Step(5, "Data Access → Add or remove scopes. Filter for \"googlehealth\" and tick the four that end in .readonly: activity_and_fitness, health_metrics_and_measurements, sleep, location. Update, then Save.")
                Step(6, "Clients → Create client → Application type: Android. Paste the package name and SHA-1 below, then Create.")
                CopyLine("Package name", PACKAGE) { copy("Package name", PACKAGE) }
                CopyLine("SHA-1", SHA1) { copy("SHA-1", SHA1) }
                Step(7, "Come back here and tap Connect Google Health. You'll see \"Google hasn't verified this app\" — that's expected for your own private project. Tap Continue, tick all boxes, Continue.")
                Text("Google may ask you to sign in again about once a week while the project is in Testing — the app will tell you.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row {
        Text("$n", style = MaterialTheme.typography.titleLarge, color = Palette.Volt, modifier = Modifier.width(26.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.Text)
    }
}

@Composable
private fun CopyLine(label: String, value: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Bg).clickable(onClick = onCopy).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Eyebrow(label)
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Palette.Text)
        }
        Icon(Icons.Rounded.ContentCopy, "Copy", tint = Palette.Volt)
    }
}
