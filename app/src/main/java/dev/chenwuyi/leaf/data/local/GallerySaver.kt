package dev.chenwuyi.leaf.data.local

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Puts a photo in the phone's gallery (Pictures/Leaf). Android 10+ needs no permission for this. */
object GallerySaver {
    suspend fun save(context: Context, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        // Before Android 10 this would need the storage permission, which Leaf does not ask for.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        val png = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "leaf-${System.currentTimeMillis()}.${if (png) "png" else "jpg"}")
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Leaf")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        runCatching {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse {
            resolver.delete(uri, null, null)
            false
        }
    }
}
