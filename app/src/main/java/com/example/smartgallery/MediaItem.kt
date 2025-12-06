package com.example.smartgallery.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores per-media scan results and metadata for incremental scanning.
 */
@Entity(
    tableName = "media_items",
    indices = [Index(value = ["contentUri"], unique = true)]
)
data class MediaItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val contentUri: String,         // MediaStore content URI as string (unique)
    val sha256: String?,            // optional - computed when needed
    val dhash: String?,             // perceptual hash
    val lapVariance: Double?,       // raw laplacian variance
    val isBlurry: Boolean?,         // latest blurry decision

    val width: Int?,
    val height: Int?,
    val sizeBytes: Long?,           // MediaStore size
    val dateModified: Long?,        // MediaStore date_modified (seconds)

    val lastScannedAt: Long?,       // epoch millis when this entry was last scanned
    val scanVersion: Int = 1        // increment when detection logic changes
)
