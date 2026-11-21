package com.local.assistant.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * A photo as Luna's server takes it: `{mime, data}`, a base64 JPEG. The server's limit is 2 MB a
 * request, so it is shrunk first: to [pixels] a side, which leaves a 1600-pixel photo of an ID
 * card around 300–600 KB.
 */
internal object ServerPhoto {

    private const val TAG = "ServerPhoto"

    /** For reading what's written on a photo (an ID number, a mark sheet): small print stays sharp. */
    const val READ_PIXELS = 1600

    /** [path] no bigger than [pixels] a side; null when it can't be read. */
    fun of(path: String, pixels: Int): Map<String, String>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= pixels) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = pixels.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        mapOf("mime" to "image/jpeg", "data" to Base64.encodeToString(bytes, Base64.NO_WRAP))
    }.onFailure { Log.w(TAG, "Couldn't read the photo to send", it) }.getOrNull()
}
