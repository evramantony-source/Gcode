package dev.gcode.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Turns a picked image into an inline Gemini part: downscaled to [MAX_SIDE] px and JPEG-encoded. */
object ImageAttachments {
    const val MAX_SIDE = 1536
    private const val QUALITY = 85

    suspend fun load(context: Context, uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@withContext null
        val (w, h) = scaled(decoded.width, decoded.height)
        val bitmap = if (w == decoded.width && h == decoded.height) decoded else decoded.scale(w, h)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        Attachment("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "${w}×$h")
    }

    /** Power-of-two sampling that keeps the image at least [MAX_SIDE] on its longer side. */
    fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2
        return sample
    }

    /** Size after scaling the longer side down to [MAX_SIDE] (never up). */
    fun scaled(width: Int, height: Int): Pair<Int, Int> {
        val longer = maxOf(width, height)
        if (longer <= MAX_SIDE) return width to height
        val ratio = MAX_SIDE.toDouble() / longer
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }
}
