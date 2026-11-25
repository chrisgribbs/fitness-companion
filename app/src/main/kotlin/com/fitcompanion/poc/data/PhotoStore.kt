package com.fitcompanion.poc.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File

/**
 * Photos and notes attached to activities. Everything lives in app-private storage:
 * other apps can't see it, it isn't backed up, it's never sent to the AI, and
 * "Delete all local data" removes it. Uninstalling the app removes it too.
 */
class PhotoStore(private val context: Context) {
    private val root = File(context.filesDir, "photos").apply { mkdirs() }
    private val notesFile = File(context.filesDir, "notes.json")

    private fun dir(activityId: String) = File(root, activityId.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }

    fun photos(activityId: String): List<File> =
        dir(activityId).listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name }.orEmpty()

    fun allPhotos(): Map<String, List<File>> =
        root.listFiles()?.filter { it.isDirectory }?.associate { d -> d.name to d.listFiles { f -> f.extension == "jpg" }!!.sortedBy { it.name } }.orEmpty()

    /** A new empty file + a content:// URI the camera app can write into. */
    fun newPhotoTarget(activityId: String): Pair<File, Uri> {
        val f = File(dir(activityId), "${System.currentTimeMillis()}.jpg")
        return f to FileProvider.getUriForFile(context, "${context.packageName}.photos", f)
    }

    /** Copies a photo picked from the gallery into private storage. */
    fun importFrom(activityId: String, uri: Uri): File? {
        val f = File(dir(activityId), "${System.currentTimeMillis()}.jpg")
        return runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
            f
        }.getOrNull()
    }

    fun delete(file: File) { if (file.canonicalPath.startsWith(root.canonicalPath)) file.delete() }

    fun note(activityId: String): String = runCatching { JSONObject(notesFile.readText()).optString(activityId) }.getOrDefault("")

    fun setNote(activityId: String, text: String) {
        val o = runCatching { JSONObject(notesFile.readText()) }.getOrDefault(JSONObject())
        if (text.isBlank()) o.remove(activityId) else o.put(activityId, text.trim())
        notesFile.writeText(o.toString())
    }

    fun deleteAll() { root.deleteRecursively(); root.mkdirs(); notesFile.delete() }

    companion object {
        /** Small, correctly rotated thumbnail. Call off the main thread. */
        fun thumbnail(file: File, maxPx: Int = 600): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val rotation = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        }.getOrNull()
    }
}
