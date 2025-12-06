package com.example.smartgallery.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {

    @Query("SELECT * FROM media_items WHERE contentUri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): MediaItem?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: MediaItem): Long

    @Update
    suspend fun update(item: MediaItem)

    @Transaction
    suspend fun upsert(item: MediaItem) {
        val existing = findByUri(item.contentUri)
        if (existing == null) {
            insert(item)
        } else {
            // preserve primary id
            val new = item.copy(id = existing.id)
            update(new)
        }
    }

    @Query("DELETE FROM media_items WHERE contentUri NOT IN (:uris)")
    suspend fun deleteNotIn(uris: List<String>)

    @Query("SELECT COUNT(*) FROM media_items")
    suspend fun countAll(): Int

    @Query("SELECT * FROM media_items ORDER BY lastScannedAt DESC LIMIT :limit")
    fun recentScans(limit: Int): Flow<List<MediaItem>>
}
