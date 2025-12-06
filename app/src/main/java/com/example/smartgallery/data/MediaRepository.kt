package com.example.smartgallery.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * MediaRepository: DB operations + quarantine move/restore helpers.
 *
 * Note: quarantine lifetime default is 30 days (configurable).
 */
private const val DEFAULT_QUARANTINE_DAYS = 30L
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

class MediaRepository(
    private val dao: MediaItemDao,
    private val qDao: QuarantineDao,
    private val appContext: Context
) {

    // ---------- Incremental logic ----------
    suspend fun needsProcessing(contentUri: String, sizeBytes: Long?, dateModified: Long?, currentScanVersion: Int): Boolean =
        withContext(Dispatchers.IO) {
            val existing = dao.findByUri(contentUri)
            if (existing == null) return@withContext true
            if (existing.scanVersion != currentScanVersion) return@withContext true
            if (existing.dateModified == null || dateModified == null) return@withContext true
            if (existing.dateModified != dateModified) return@withContext true
            if (existing.sizeBytes == null || sizeBytes == null) return@withContext true
            if (existing.sizeBytes != sizeBytes) return@withContext true
            return@withContext false
        }

    suspend fun saveScanResult(item: MediaItem) = withContext(Dispatchers.IO) {
        dao.upsert(item)
    }

    suspend fun cleanupMissing(urisPresent: List<String>) = withContext(Dispatchers.IO) {
        if (urisPresent.isNotEmpty()) dao.deleteNotIn(urisPresent)
    }

    fun recentScansFlow(limit: Int) = dao.recentScans(limit)

    suspend fun findByUri(uri: String) = withContext(Dispatchers.IO) { dao.findByUri(uri) }

    // ---------- Quarantine ----------
    /**
     * Copy the contentUri to an app-private quarantine file and record a QuarantineItem.
     * Returns the QuarantineItem if success, or null on failure.
     *
     * The caller should then perform actual deletion of the source (system delete) and call
     * finalizeQuarantineIfDeleted(...) to persist the QuarantineItem in DB (we return it now so caller can use it).
     */
    suspend fun moveToQuarantine(contentUri: String, contentResolver: ContentResolver, quarantineDays: Long = DEFAULT_QUARANTINE_DAYS): QuarantineItem? =
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(contentUri)
                val inStream = contentResolver.openInputStream(uri) ?: return@withContext null
                // create quarantine dir
                val qdir = File(appContext.filesDir, "quarantine")
                if (!qdir.exists()) qdir.mkdirs()
                // filename: timestamp_originalname
                val name = uri.lastPathSegment ?: "media"
                val outFile = File(qdir, "${System.currentTimeMillis()}_$name")
                inStream.use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val now = System.currentTimeMillis()
                val expires = now + max(1L, quarantineDays) * MILLIS_PER_DAY
                val qi = QuarantineItem(
                    originalContentUri = contentUri,
                    backupPath = outFile.absolutePath,
                    deletedAtMillis = now,
                    expiresAtMillis = expires
                )
                // we DO NOT insert into DB until deletion is finalized; return the item for caller
                return@withContext qi
            } catch (t: Throwable) {
                t.printStackTrace()
                return@withContext null
            }
        }

    /**
     * Finalize quarantine after the source was actually deleted.
     * Inserts a QuarantineItem row (so we can restore later) and removes the MediaItem.
     */
    suspend fun finalizeQuarantineIfDeleted(qi: QuarantineItem) = withContext(Dispatchers.IO) {
        val id = qDao.insert(qi)
        // remove MediaItem row referencing original uri
        dao.deleteByUris(listOf(qi.originalContentUri))
        id
    }
    suspend fun findQuarantineByOriginalUri(uri: String): QuarantineItem? =
        withContext(Dispatchers.IO) {
            qDao.findByOriginalUri(uri)
        }


    /**
     * Restore a quarantined backup back into MediaStore.
     * Returns the new contentUri string, or null on failure.
     */
    suspend fun restoreFromQuarantine(qi: QuarantineItem): String? = withContext(Dispatchers.IO) {
        try {
            val f = File(qi.backupPath)
            if (!f.exists()) return@withContext null
            // insert into MediaStore (simple, images only)
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, guessMimeType(f.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SmartGalleryRestores")
            }

            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            resolver.openOutputStream(uri)?.use { out ->
                f.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            // remove quarantine file and DB row
            f.delete()
            val existing = qDao.findByOriginalUri(qi.originalContentUri)
            if (existing != null) qDao.deleteByIds(listOf(existing.id))
            return@withContext uri.toString()
        } catch (t: Throwable) {
            t.printStackTrace()
            return@withContext null
        }
    }

    /**
     * Purge expired quarantine files and DB rows.
     * Returns number of purged items.
     */
    suspend fun purgeExpiredQuarantine(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val expired = qDao.findExpired(now)
        var purged = 0
        for (q in expired) {
            try {
                val f = File(q.backupPath)
                if (f.exists()) f.delete()
                qDao.deleteByIds(listOf(q.id))
                purged++
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        purged
    }

    private fun guessMimeType(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            else -> "application/octet-stream"
        }
    }
}
