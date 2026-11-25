package com.fitcompanion.poc.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fitcompanion.core.ActivityRecord
import com.fitcompanion.poc.AppController
import com.fitcompanion.poc.data.PhotoStore
import com.fitcompanion.poc.ui.theme.BigStat
import com.fitcompanion.poc.ui.theme.Eyebrow
import com.fitcompanion.poc.ui.theme.GhostButton
import com.fitcompanion.poc.ui.theme.Palette
import com.fitcompanion.poc.ui.theme.SportCard
import com.fitcompanion.poc.ui.theme.Tag
import com.fitcompanion.poc.ui.theme.VoltButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration

/** Photo loaded off the main thread; shows a dark placeholder until ready. */
@Composable
fun PhotoThumb(file: File, modifier: Modifier = Modifier, maxPx: Int = 500, fit: Boolean = false) {
    val bmp by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { PhotoStore.thumbnail(file, maxPx)?.asImageBitmap() }
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHigh)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = if (fit) ContentScale.Fit else ContentScale.Crop) }
    }
}

@Composable
fun PhotoStrip(files: List<File>, size: Dp = 76.dp, onClick: (() -> Unit)? = null) {
    if (files.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(files, key = { it.path }) { f ->
            PhotoThumb(f, Modifier.size(size).let { m -> if (onClick != null) m.clickable(onClick = onClick) else m })
        }
    }
}

/**
 * Full-screen activity journal: the numbers, your photos (camera or gallery) and a note.
 * Photos and notes stay in app-private storage and are never sent to the AI.
 */
@Composable
fun ActivityJournal(app: AppController, a: ActivityRecord, onClose: () -> Unit) {
    val files = remember(app.journalVersion, a.id) { app.photos.photos(a.id) }
    var note by remember(a.id) { mutableStateOf(app.photos.note(a.id)) }
    var pending by remember { mutableStateOf<File?>(null) }
    var viewing by remember { mutableStateOf<File?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending
        if (!ok || f == null || f.length() == 0L) f?.delete()
        pending = null
        app.journalChanged()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris: List<Uri> ->
        uris.forEach { app.photos.importFrom(a.id, it) }
        app.journalChanged()
    }

    Dialog(onDismissRequest = { app.setNote(a.id, note); onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Palette.Bg).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(a.kind.color().copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(a.kind.icon(), null, tint = a.kind.color())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(friendlyDate(a, app.zone))
                    Text(a.kind.label().uppercase(), style = MaterialTheme.typography.headlineLarge)
                }
                IconButton(onClick = { app.setNote(a.id, note); onClose() }) { Icon(Icons.Rounded.Close, "Close", tint = Palette.Text) }
            }
            if (a.isDemo) Tag("DEMO", Palette.Warn)

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column { Eyebrow("Distance"); BigStat(a.distanceMeters?.let { kmNumber(it) }, "km", big = true, color = a.kind.color()) }
                Column { Eyebrow("Time"); BigStat(duration(Duration.between(a.start, a.end)), "") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column { Eyebrow("Pace"); BigStat(paceText(a), "/km") }
                Column { Eyebrow("Climb"); BigStat(a.ascentMeters?.let { "%.0f".format(it) }, "m", a.ascentQuality) }
            }

            Eyebrow("Photos")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VoltButton("Camera", icon = Icons.Rounded.PhotoCamera, onClick = {
                    val (f, uri) = app.photos.newPhotoTarget(a.id); pending = f; camera.launch(uri)
                })
                GhostButton("Gallery", icon = Icons.Rounded.PhotoLibrary, onClick = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
            }
            if (files.isEmpty()) {
                Text("No photos yet. Snap the view from the top of the hill, the beach at the finish…",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            } else {
                files.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { f -> PhotoThumb(f, Modifier.weight(1f).aspectRatio(1f).clickable { viewing = f }) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Eyebrow("How did it feel?")
            OutlinedTextField(
                note, { note = it }, Modifier.fillMaxWidth(), minLines = 3,
                placeholder = { Text("e.g. felt strong, left ankle a bit sore after 5 km", color = Palette.TextDim) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Volt, unfocusedBorderColor = Palette.Outline),
                shape = RoundedCornerShape(16.dp),
            )
            Text("Photos and notes stay on this phone. They're never sent to the AI.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Spacer(Modifier.height(24.dp))
        }
    }

    viewing?.let { f ->
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Palette.Bg)) {
                PhotoThumb(f, Modifier.fillMaxSize(), maxPx = 2000, fit = true)
                Row(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    IconButton(onClick = { app.photos.delete(f); app.journalChanged(); viewing = null }) {
                        Icon(Icons.Rounded.Delete, "Delete photo", tint = Palette.Heart)
                    }
                    IconButton(onClick = { viewing = null }) { Icon(Icons.Rounded.Close, "Close", tint = Palette.Text) }
                }
            }
        }
    }
}
