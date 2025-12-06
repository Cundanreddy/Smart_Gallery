package com.example.smartgallery.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quarantine_items")
data class QuarantineItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val originalContentUri: String,  // original MediaStore URI string
    val backupPath: String,          // absolute path in app files dir
    val deletedAtMillis: Long,       // epoch millis when moved to quarantine
    val expiresAtMillis: Long        // epoch millis when backup can be purged
)
