package com.example.smartgallery.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class MediaRepository(val dao: MediaItemDao) {

    /**
     * Decide whether we must rescan this media item.
     *
     * Use meta heuristics: if size and dateModified match, skip. If scanVersion changed, rescan.
     */
    suspend fun needsProcessing(contentUri: String, sizeBytes: Long?, dateModified: Long?, currentScanVersion: Int): Boolean =
        withContext(Dispatchers.IO) {
            val existing = dao.findByUri(contentUri)
            if (existing == null) return@withContext true
            // If scan version changed -> reprocess
            if (existing.scanVersion != currentScanVersion) return@withContext true
            // if dateModified or size differ -> reprocess
            if (existing.dateModified == null || dateModified == null) return@withContext true
            if (existing.dateModified != dateModified) return@withContext true
            if (existing.sizeBytes == null || sizeBytes == null) return@withContext true
            if (existing.sizeBytes != sizeBytes) return@withContext true
            // otherwise unchanged
            return@withContext false
        }

    suspend fun saveScanResult(item: MediaItem) {
        dao.upsert(item)
    }

    suspend fun cleanupMissing(urisPresent: List<String>) {
        dao.deleteNotIn(urisPresent)
    }
}
