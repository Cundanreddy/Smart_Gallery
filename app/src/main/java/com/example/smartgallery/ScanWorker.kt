package com.example.smartgallery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import com.example.smartgallery.data.AppDatabase
import com.example.smartgallery.data.MediaRepository

class ScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val repository: MediaRepository by lazy {
        val db = AppDatabase.get(applicationContext)
        MediaRepository(db.mediaItemDao(), db.quarantineDao(), applicationContext)
    }

    override suspend fun doWork(): Result {
        val notification = NotificationCompat.Builder(applicationContext, NotificationHelper.CHANNEL_ID)
            .setContentTitle("Smart Gallery — Scheduled Scan")
            .setContentText("Preparing...")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
        setForeground(ForegroundInfo(NotificationHelper.NOTIFICATION_ID, notification))

        return try {
            val scanner = MediaScannerIncremental(applicationContext.contentResolver, repository)
            scanner.scanImagesIncremental { p ->
                // scanner persists into DB already; still emit to bus
                ScanBus.emit(p)
            }
            Result.success()
        } catch (t: Throwable) {
            t.printStackTrace()
            Result.retry()
        }
    }
}
