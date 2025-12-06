package com.example.smartgallery.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_items",
    indices = [Index(value = ["contentUri"], unique = true)]
)
data class MediaItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contentUri: String,
    val sha256: String? = null,
    val dhash: String? = null,
    val lapVariance: Double? = null,
    val isBlurry: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null,
    val dateModified: Long? = null,
    val lastScannedAt: Long? = null,
    val scanVersion: Int = 1
)
