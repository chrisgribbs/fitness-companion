package com.fitcompanion.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.AppController.Health
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.SportCard
import com.fitcompanion.poc.ui.theme.VoltButton
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun Health.color(): Color = when (this) {
    Health.UP_TO_DATE -> Palette.Good
    Health.BEHIND -> Palette.Warn
    Health.NO_DATA -> Palette.TextDim
    Health.NO_PERMISSION -> Palette.Heart
}

fun Health.icon(): ImageVector = when (this) {
    Health.UP_TO_DATE -> Icons.Rounded.CheckCircle
    Health.BEHIND -> Icons.Rounded.ErrorOutline
    Health.NO_DATA -> Icons.Rounded.HelpOutline
    Health.NO_PERMISSION -> Icons.Rounded.Block
}

/** Compact status strip for the top of Today: one glance tells you if the numbers below are current. */
@Composable
fun SyncBanner(app: AppController, onDetails: () -> Unit) {
    val scope = rememberCoroutineScope()
    val (health, text) = app.syncHeadline
    val checked = app.lastSynced?.format(DateTimeFormatter.ofPattern("h:mm a"))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(health.color().copy(alpha = 0.12f))
            .border(1.dp, health.color().copy(alpha = 0.35f), RoundedCornerShape(18.dp)).clickable(onClick = onDetails).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (app.busy) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Volt, strokeWidth = 2.dp)
            else Icon(health.icon(), null, tint = health.color(), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (app.busy) "Syncing…" else text, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                if (checked != null && !app.busy) Text("Checked Health Connect at $checked", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextDim)
        }
        if (health == Health.BEHIND && !app.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction("Open Google Health", Icons.Rounded.OpenInNew) { if (!app.openSourceApp()) onDetails() }
                SmallAction("Sync again", Icons.Rounded.Sync) { scope.launch { app.refresh() } }
            }
        }
    }
}

@Composable
private fun SmallAction(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.SurfaceHigh).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.Volt, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Text)
    }
}

/** Full per-type status for Setup. */
@Composable
fun SyncStatusCard(app: AppController, onAllowAccess: () -> Unit) {
    val scope = rememberCoroutineScope()
    val (health, headline) = app.syncHeadline
    SportCard {
        Eyebrow("Sync status", health.color())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(health.icon(), null, tint = health.color(), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (health == Health.UP_TO_DATE) "ALL UP TO DATE" else headline.uppercase(), style = MaterialTheme.typography.titleLarge)
        }
        app.lastSynced?.let {
            Text("Last checked ${it.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a"))}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }

        if (app.typeStatuses.isEmpty()) {
            Text("Tap Sync now to check each type of data.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        app.typeStatuses.forEach { t -> StatusRow(t) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VoltButton(if (app.busy) "Syncing…" else "Sync now", icon = Icons.Rounded.Sync, enabled = !app.busy, onClick = { scope.launch { app.refresh() } })
            if (app.typeStatuses.any { it.health == Health.NO_PERMISSION }) GhostButton("Allow access", onClick = onAllowAccess)
        }
        if (health == Health.BEHIND) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HOW TO FIX ⚠", style = MaterialTheme.typography.labelLarge, color = Palette.Warn)
                Text("1. Check the missing workout shows in the Google Health app.\n" +
                    "2. Open Google Health and pull down to refresh — it shares new data with Health Connect after it syncs.\n" +
                    "3. In Google Health: Settings → Manage Health Connect → make sure Exercise, Sleep and Heart rate are switched on, and allow background access.\n" +
                    "4. Come back and tap Sync now.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Open Google Health", icon = Icons.Rounded.OpenInNew, onClick = { app.openSourceApp() })
                    GhostButton("Health Connect", onClick = { app.openHealthConnectSettings() })
                }
            }
        }
        Text("How syncing works: Fitbit → Google Health app → Health Connect (on this phone) → this app. " +
            "✓ means the newest data has arrived. ⚠ means Google Health hasn't passed newer data to Health Connect yet — this app can only show what it's been given.",
            style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
    }
}

@Composable
private fun StatusRow(t: AppController.TypeStatus) {
    val today = LocalDate.now()
    val whenText = t.newest?.let {
        val d = it.toLocalDate()
        val day = when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM")) }
        "$day ${it.format(DateTimeFormatter.ofPattern("h:mm a"))}"
    }
    val detail = when (t.health) {
        Health.UP_TO_DATE -> "Up to date · newest $whenText"
        Health.BEHIND -> "${t.daysBehind} days behind · newest $whenText"
        Health.NO_DATA -> "Allowed, but nothing in the last 30 days"
        Health.NO_PERMISSION -> "Not allowed — tap Allow access"
    } + (t.source?.let { " · from $it" } ?: "")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(t.health.color().copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(t.health.icon(), null, tint = t.health.color(), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.label, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (t.health == Health.UP_TO_DATE) Palette.TextDim else t.health.color())
        }
    }
}

/** One line per connection: what it is, whether it's working. */
@Composable
fun ConnectionRow(title: String, detail: String, ok: Boolean?, onClick: (() -> Unit)? = null) {
    val color = when (ok) { true -> Palette.Good; false -> Palette.Warn; null -> Palette.TextDim }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextDim)
    }
}
