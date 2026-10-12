package com.example.couplewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Optional foreground service: keeps the relay connection so the lock screen updates while the app is closed. */
class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SyncHub.init(this)
        if (!SyncHub.prefs.liveService || !SyncHub.isPaired) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        SyncHub.acquire("service")
        return START_STICKY
    }

    override fun onDestroy() {
        SyncHub.release("service")
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("sync", "Синхронизация", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, "sync")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val n = builder
            .setContentTitle("Общий рисунок")
            .setContentText("Экраны обновляются")
            .setSmallIcon(R.drawable.ic_brush)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }
}
