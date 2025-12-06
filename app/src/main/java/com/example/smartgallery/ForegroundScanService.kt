package com.example.smartgallery

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.smartgallery.data.AppDatabase
import com.example.smartgallery.data.MediaItem
import com.example.smartgallery.data.MediaRepository
import kotlinx.coroutines.*

class ForegroundScanService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scanJob: Job? = null
    private lateinit var repository: MediaRepository

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(applicationContext)
        repository = MediaRepository(db.mediaItemDao())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> startScan()
            ACTION_STOP  -> stopScanRequest()
        }
        return START_STICKY
    }

    private fun startScan() {
        if (scanJob?.isActive == true) return

        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildStartNotification(this, "Starting scan..."))

        scanJob = serviceScope.launch {
            try {
                val scanner = MediaScannerIncremental(applicationContext.contentResolver, repository)
                scanner.scanImagesIncremental @androidx.annotation.RequiresPermission(android.Manifest.permission.POST_NOTIFICATIONS) { p ->
                    // persist to DB already done inside scanner; but optionally ensure extra fields:
                    // Emit to UI
                    runBlocking { ScanBus.emit(p) }
                    // update notification occasionally
                    if (p.index % 10 == 0 || p.index == p.totalEstimated) {
                        val nm = NotificationManagerCompat.from(this@ForegroundScanService)
                        nm.notify(NotificationHelper.NOTIFICATION_ID,
                            NotificationHelper.buildStartNotification(this@ForegroundScanService, "Scanning ${p.index}/${p.totalEstimated}")
                        )
                    }
                    if (!isActive) throw CancellationException("service scan cancelled")
                }
            } catch (e: CancellationException) {
                // cancelled
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun stopScanRequest() {
        scanJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START = "com.example.smartgallery.action.START_SCAN"
        const val ACTION_STOP = "com.example.smartgallery.action.STOP_SCAN"

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
