package com.example.smartgallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationSetup {
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                ForegroundScanService.CHANNEL_ID,
                "Smart Gallery Scans",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for Smart Gallery scan progress"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
