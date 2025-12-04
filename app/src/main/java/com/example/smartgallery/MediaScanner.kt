package com.example.smartgallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

data class ScanProgress(
    val id: Long,
    val uri: Uri,
    val displayName: String?,
    val thumbnail: Bitmap?,
    val sha256: String?,
    val dhash: String?,
    val lapVariance: Double,
    val isBlurry: Boolean,
    val index: Int,
    val totalEstimated: Int // may be 0 if unknown
)

class MediaScanner(private val resolver: ContentResolver) {

    suspend fun scanImages(perImage: suspend (ScanProgress) -> Unit): List<ScanProgress> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ScanProgress>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            val cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sortOrder)
            cursor?.use { c ->
                val totalEstimate = c.count
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val wIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                var idx = 0
                while (c.moveToNext()) {
                    idx++
                    val id = c.getLong(idIdx)
                    val name = c.getString(nameIdx)
                    val w = if (wIdx >= 0) c.getInt(wIdx) else 0
                    val h = if (hIdx >= 0) c.getInt(hIdx) else 0
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())

                    // load small thumbnail
                    val thumb = resolver.openInputStream(uri)?.use { stream ->
                        decodeSampledBitmapFromStream(stream, 200, 200)
                    }

                    // compute sha256
                    val sha = resolver.openInputStream(uri)?.use { stream ->
                        computeSha256(stream)
                    }

                    // compute dhash & lap variance via native bridge
                    val dh = thumb?.let { ImageHashing.dhashFromBitmap(it) }
                    val variance = thumb?.let { ImageHashing.lapVarianceFromBitmap(it) } ?: 0.0
                    val blurry = thumb?.let { ImageHashing.isBlurryFromBitmap(it, 100.0f) } ?: false

                    val progress = ScanProgress(id, uri, name, thumb, sha, dh, variance, blurry, idx, totalEstimate)
                    results.add(progress)
                    perImage(progress) // deliver progress to UI

                }
            }
            results
        }
    }

    private fun computeSha256(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        var read = stream.read(buf)
        while (read > 0) {
            md.update(buf, 0, read)
            read = stream.read(buf)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // decode helpers
    private fun decodeSampledBitmapFromStream(stream: InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
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
