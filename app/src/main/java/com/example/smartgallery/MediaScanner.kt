package com.example.smartgallery

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

data class MediaAsset(
    val id: Long,
    val uri: Uri,
    val displayName: String?,
    val width: Int,
    val height: Int,
    val sha256: String?,
    val dhash: String?,
    val isBlurry: Boolean,
    val thumbnailBitmap: Bitmap?
)

class MediaScanner(private val resolver: ContentResolver) {

    suspend fun scanImages(progress: (String) -> Unit = { }) : List<MediaAsset> {
        return withContext(Dispatchers.IO) {
            val list = mutableListOf<MediaAsset>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            val query = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sortOrder)
            query?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val wIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                var count=0
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx)
                    val w = if (wIdx >= 0) cursor.getInt(wIdx) else 0
                    val h = if (hIdx >= 0) cursor.getInt(hIdx) else 0
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    // load a compressed thumbnail to do fast checks
                    val thumb = resolver.openInputStream(contentUri)?.use { stream ->
                        decodeSampledBitmapFromStream(stream, 200, 200)
                    }

                    // compute sha256 on stream (small memory)
                    val sha = resolver.openInputStream(contentUri)?.use { stream ->
                        computeSha256(stream)
                    }

                    // compute dHash and blur in Kotlin (replace later with native)
                    val dh = thumb?.let { ImageHashing.dhashFromBitmap(it) }
                    val blurry = thumb?.let { ImageHashing.isBlurryFromBitmap(it, 50.0f) } ?: false

                    list.add(MediaAsset(id, contentUri, name, w, h, sha, dh, blurry, thumb))
                    count++
                    if (count % 50 == 0) progress("scanned $count")
                }
            }
            list
        }
    }

    // Efficient stream-based SHA-256
    fun computeSha256(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        var read = stream.read(buf)
        while (read > 0) {
            md.update(buf, 0, read)
            read = stream.read(buf)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // sample decode helper
    fun decodeSampledBitmapFromStream(stream: InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        // read into byte array because InputStream cannot rewind easily
        val bytes = stream.readBytes()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
