package com.fitcompanion.poc.ui

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
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.ProgressBar
import com.fitcompanion.poc.ui.theme.SportCard
import com.fitcompanion.poc.ui.theme.VoltButton
import kotlinx.coroutines.launch

/** Volt banner shown at the top of Today when a newer build is available. */
@Composable
fun UpdateBanner(app: AppController) {
    val a = app.update ?: return
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.VoltGlow).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = Palette.Bg)
            Spacer(Modifier.width(8.dp))
            Text("UPDATE AVAILABLE · BUILD ${a.build}", style = MaterialTheme.typography.titleLarge, color = Palette.Bg, modifier = Modifier.weight(1f))
        }
        app.updateProgress?.let { ProgressBar(it, androidx.compose.ui.graphics.SolidColor(Palette.Bg)) }
        app.updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Bg) }
        if (app.updateProgress == null) {
            Text("UPDATE NOW", style = MaterialTheme.typography.labelLarge, color = Palette.Volt,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Bg)
                    .clickable { scope.launch { app.installUpdate() } }.padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

/** Version card for the Setup tab. */
@Composable
fun UpdateCard(app: AppController) {
    val scope = rememberCoroutineScope()
    SportCard(padding = 16.dp) {
        Eyebrow("App version")
        Text("BUILD ${app.updater.currentBuild}", style = MaterialTheme.typography.headlineSmall)
        if (app.update != null) {
            VoltButton("Update to build ${app.update!!.build}", icon = Icons.Rounded.SystemUpdate, onClick = { scope.launch { app.installUpdate() } })
        } else {
            GhostButton("Check for updates", icon = Icons.Rounded.SystemUpdate, onClick = { scope.launch { app.checkForUpdate(manual = true) } })
        }
        app.updateProgress?.let { ProgressBar(it, Palette.VoltGlow) }
        app.updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
        Text("Updates come from github.com/chrisgribbs/fitness-companion-releases and keep all your data.",
            style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
    }
}
