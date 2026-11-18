package com.woundcopilot.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Saves a copy of a photo, recording or text to the phone's own Pictures, Music or Download folder, or shares a copy. Always a copy; the app's own file is untouched. */
object PhoneSave {
    /** Returns a short message for the screen: what was saved and where, or why it could not be. */
    fun save(context: Context, file: File, mime: String): String {
        if (Build.VERSION.SDK_INT < 29) return "Saving to the phone needs Android 10 or newer. Use Share instead."
        return try {
            val (collection, folder) = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Amilya"
                mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Amilya"
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/Amilya"
            }
            val name = "Amilya-" + System.currentTimeMillis() + "-" + file.name.takeLast(40)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            }
            val uri = context.contentResolver.insert(collection, values) ?: return "The file could not be saved to the phone."
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: return "The file could not be saved to the phone."
            "Saved to your phone in $folder."
        } catch (_: Exception) { "The file could not be saved to the phone." }
    }

    fun saveText(context: Context, text: String, label: String): String {
        val file = File(context.cacheDir, "exports").apply { mkdirs() }.let { File(it, "$label-${System.currentTimeMillis()}.txt") }
        return try { file.writeText(text); save(context, file, "text/plain") } catch (_: Exception) { "The text could not be saved to the phone." }
    }

    /** Opens the phone's share menu (email, messages, any app) with a copy of the file. Returns an error message or null. */
    fun share(context: Context, file: File, mime: String, title: String): String? = try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val copy = File(dir, "share-" + System.currentTimeMillis() + "." + file.extension.ifBlank { "bin" }).also { file.copyTo(it, true) }
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", copy)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, title))
        null
    } catch (_: Exception) { "It could not be shared." }
}
