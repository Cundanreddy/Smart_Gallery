package com.example.smartgallery

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import android.app.Notification
import androidx.core.content.ContextCompat

class ForegroundScanService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scanJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null // not a bound service

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when(action) {
            ACTION_START -> startScan()
            ACTION_STOP  -> stopScanRequest()
        }
        return START_STICKY
    }

    private fun startScan() {
        if (scanJob?.isActive == true) return
        startForeground(NOTIFICATION_ID, buildNotification("Starting scan...", 0, 0))
        scanJob = serviceScope.launch {
            try {
                val scanner = MediaScanner(applicationContext.contentResolver)
                scanner.scanImages { p ->
                    // emit to app bus for UI
                    runBlocking { ScanBus.emit(p) } // small sync emit (fast)
                    // update notification every few items
                    if (p.index % 5 == 0 || p.index == p.totalEstimated) {
                        updateNotification("Scanning: ${p.index}/${p.totalEstimated}", p.index, p.totalEstimated)
                    }
                    // cooperative cancellation check
                    if (!isActive) throw CancellationException("Service scan cancelled")
                }
                // finished
                updateNotification("Scan finished", 0, 0, finished = true)
            } catch (e: CancellationException) {
                updateNotification("Scan stopped", 0, 0, finished = true)
            } catch (t: Throwable) {
                updateNotification("Scan failed: ${t.localizedMessage}", 0, 0, finished = true)
            } finally {
                // stop service after small delay to allow user to read
                delay(2000)
                stopSelf()
            }
        }
    }

    private fun stopScanRequest() {
        scanJob?.cancel()
    }

    private fun buildNotification(title: String, progress: Int, total: Int): Notification {
        val stopIntent = Intent(this, ForegroundScanService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val contentIntent = PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Gallery")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStop)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (total > 0) {
            val percent = ((progress.toDouble() / total.toDouble()) * 100).toInt()
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(title: String, progress: Int, total: Int, finished: Boolean = false) {
        val nm = NotificationManagerCompat.from(this)
        val notif = buildNotification(title, progress, total)
        if (finished) {
            // make it dismissible
            val done = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Smart Gallery")
                .setContentText(title)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(NOTIFICATION_ID, done)
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            nm.notify(NOTIFICATION_ID, notif)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.smartgallery.action.START_SCAN"
        const val ACTION_STOP  = "com.smartgallery.action.STOP_SCAN"
        const val CHANNEL_ID = "smartgallery_scan_channel"
        const val NOTIFICATION_ID = 0x99

        fun startService(context: Context) {
            val intent = Intent(context, ForegroundScanService::class.java).apply { action = ACTION_START }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ForegroundScanService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
