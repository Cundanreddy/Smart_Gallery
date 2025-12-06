package com.example.smartgallery


import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import com.example.smartgallery.data.MediaRepository
import com.example.smartgallery.data.AppDatabase
import com.example.smartgallery.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import kotlin.system.measureTimeMillis


//data class ScanProgress(
//    val id: Long,
//    val uri: Uri,
//    val displayName: String?,
//    val thumbnail: Bitmap?,
//    val sha256: String?,
//    val dhash: String?,
//    val lapVariance: Double,
//    val isBlurry: Boolean,
//    val index: Int,
//    val totalEstimated: Int // may be 0 if unknown
//)

// decide a scan version: bump when algorithm changes
private const val SCAN_VERSION = 1

class MediaScannerIncremental(private val resolver: ContentResolver, private val repository: MediaRepository) {

    /**
     * Scan images incrementally. `perImage` is called for every scanned item (both skipped and processed).
     * For skipped items `sha256/dhash/lapVariance` may be null but we still emit the DB values to UI if available.
     */
    suspend fun scanImagesIncremental(perImage: (ScanProgress) -> Unit) : List<ScanProgress> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ScanProgress>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            val cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sortOrder)
            val presentUris = mutableListOf<String>()
            var idx = 0
            cursor?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val wIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val modIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                while (c.moveToNext()) {
                    idx++
                    val id = c.getLong(idIdx)
                    val name = c.getString(nameIdx)
                    val w = if (wIdx >= 0) c.getInt(wIdx) else 0
                    val h = if (hIdx >= 0) c.getInt(hIdx) else 0
                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else null
                    val dateModified = if (modIdx >= 0) c.getLong(modIdx) else null
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val uriStr = uri.toString()
                    presentUris.add(uriStr)

                    val need = repository.needsProcessing(uriStr, size, dateModified, SCAN_VERSION)
                    if (!need) {
                        // fetch from DB to emit values to UI
                        val existing = repository.dao.findByUri(uriStr) // make dao accessible or add a repo method to fetch
                        val progress = ScanProgress(id, uri, name, null, existing?.sha256, existing?.dhash, existing?.lapVariance ?: 0.0, existing?.isBlurry ?: false, idx, c.count)
                        results.add(progress)
                        perImage(progress)
                        continue
                    }

                    // Need processing: create thumbnail, compute sha, dhash, lapVariance (native)
                    val thumb = resolver.openInputStream(uri)?.use { stream ->
                        decodeSampledBitmapFromStream(stream, 200, 200)
                    }

                    val sha = resolver.openInputStream(uri)?.use { stream ->
                        computeSha256(stream)
                    }

                    val dh = thumb?.let { ImageHashing.dhashFromBitmap(it) }
                    val variance = thumb?.let { ImageHashing.lapVarianceFromBitmap(it) } ?: 0.0
                    val blurry = thumb?.let { ImageHashing.isBlurryFromBitmap(it, 100.0f) } ?: false

                    // persist result
                    val item = MediaItem(
                        contentUri = uriStr,
                        sha256 = sha,
                        dhash = dh,
                        lapVariance = variance,
                        isBlurry = blurry,
                        width = w,
                        height = h,
                        sizeBytes = size,
                        dateModified = dateModified,
                        lastScannedAt = System.currentTimeMillis(),
                        scanVersion = SCAN_VERSION
                    )
                    repository.saveScanResult(item)

                    val progress = ScanProgress(id, uri, name, thumb, sha, dh, variance, blurry, idx, c.count)
                    results.add(progress)
                    perImage(progress)
                }
            }

            // optional: cleanup DB rows for files that no longer exist
            repository.cleanupMissing(presentUris)

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