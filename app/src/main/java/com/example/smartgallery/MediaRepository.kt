package com.example.smartgallery.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val dao: MediaItemDao) {

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
        if (urisPresent.isNotEmpty()) {
            dao.deleteNotIn(urisPresent)
        }
    }

    fun recentScansFlow(limit: Int) = dao.recentScans(limit)

    suspend fun findByUri(uri: String) = withContext(Dispatchers.IO) { dao.findByUri(uri) }
}
