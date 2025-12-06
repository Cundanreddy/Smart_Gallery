package com.example.smartgallery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import com.example.smartgallery.ForegroundScanService
import com.example.smartgallery.MediaScanner
import com.example.smartgallery.ScanBus

class ScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Build foreground info
        val notification = NotificationCompat.Builder(applicationContext, ForegroundScanService.CHANNEL_ID)
            .setContentTitle("Smart Gallery — Scheduled Scan")
            .setContentText("Preparing...")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
        setForeground(ForegroundInfo(ForegroundScanService.NOTIFICATION_ID, notification))

        return try {
            val scanner = MediaScanner(applicationContext.contentResolver)
            scanner.scanImages { p ->
                // emit progress to UI via ScanBus
                ScanBus.emit(p)
            }
            // success
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}
