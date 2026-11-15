package com.termuxbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Foreground service: process ko zinda rakhta hai + control server chalata hai. */
class BrowserService : Service() {
    private var wl: PowerManager.WakeLock? = null
    private var server: ControlServer? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("bg", "Browser running", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "bg")
            .setContentTitle("Termux Browser chal raha hai")
            .setContentText("Tap karke browser kholo")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termuxbrowser:svc").apply {
            setReferenceCounted(false)
            acquire()
        }

        server = ControlServer(Cfg.token(this)).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.stop()
        try { wl?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
