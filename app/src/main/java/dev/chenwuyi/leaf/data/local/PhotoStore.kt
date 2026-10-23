package dev.chenwuyi.leaf.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Photos for scribbles. The picker's content URI only stays readable for a while, so the image is
 * copied into app storage at once, scaled to a 2400 px long edge and saved as JPEG: Notion's free
 * plan caps uploads at 5 MB.
 */
class PhotoStore(context: Context, private val http: OkHttpClient = OkHttpClient()) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "photos")

    /** Returns a file:// URI for the stored copy, or null when the image cannot be read. */
    fun import(source: String): String? {
        val uri = Uri.parse(source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null, so the stream itself says whether the image is readable.
        val stream = app.contentResolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MaxEdge) sample *= 2
        val decoded = app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = MaxEdge.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        dir.mkdirs()
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        return Uri.fromFile(file).toString()
    }

    fun read(fileUri: String): ByteArray? = Uri.parse(fileUri).path?.let(::File)?.takeIf { it.isFile }?.readBytes()

    /** Fetches an image Notion hosts, for re-uploading a restored photo. No credentials are sent. */
    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
        }.getOrNull()
    }

    fun delete(fileUri: String) {
        Uri.parse(fileUri).path?.let(::File)?.takeIf { it.parentFile == dir }?.delete()
    }

    private companion object {
        const val MaxEdge = 2400
    }
}
