package com.example.smartgallery.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface QuarantineDao {
    @Insert
    suspend fun insert(item: QuarantineItem): Long

    @Query("SELECT * FROM quarantine_items ORDER BY deletedAtMillis DESC")
    fun allFlow(): Flow<List<QuarantineItem>>

    @Query("SELECT * FROM quarantine_items WHERE originalContentUri = :uri LIMIT 1")
    suspend fun findByOriginalUri(uri: String): QuarantineItem?

    @Query("DELETE FROM quarantine_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM quarantine_items WHERE expiresAtMillis <= :now")
    suspend fun findExpired(now: Long): List<QuarantineItem>
}
