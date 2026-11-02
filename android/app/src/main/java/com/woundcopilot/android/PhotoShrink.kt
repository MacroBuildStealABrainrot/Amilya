package com.woundcopilot.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/**
 * Makes a phone photo smaller before it is sent, so it uploads quickly.
 * Long side is kept at 2000 pixels, which still shows wound detail clearly.
 * The picture is turned upright first, because the smaller copy carries no turn
 * note. If anything goes wrong the original file is sent unchanged.
 */
object PhotoShrink {
    const val LONG_SIDE = 2000
    const val SKIP_BELOW_BYTES = 600L * 1024

    fun shrunkCopy(original: File): File? = runCatching {
        if (original.length() < SKIP_BELOW_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null
        var sample = 1
        while (longSide / (sample * 2) >= LONG_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = LONG_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val turn = when (ExifInterface(original.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val matrix = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (turn != 0f) postRotate(turn) }
        val finished = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val out = File.createTempFile("upload-", ".jpg")
        out.outputStream().use { check(finished.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
        if (out.length() >= original.length()) { out.delete(); null } else out
    }.getOrNull()
}
