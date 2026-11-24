package com.ali.files.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ali.files.R
import com.ali.files.util.Notifications
import com.ali.files.util.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps the process alive while downloads run and shows their progress. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val awake by lazy { com.ali.files.util.KeepAwake(this, "downloads") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(build(Downloader.items.value))
        awake.on()
        scope.launch {
            Downloader.items.collect { list ->
                if (list.none { it.active }) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    post(build(list))
                }
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(build(Downloader.items.value))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        awake.off()
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(n: Notification) {
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    @SuppressLint("MissingPermission")
    private fun post(n: Notification) {
        if (Notifications.canPost(this)) NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
    }

    private fun build(list: List<DownloadItem>): Notification {
        val active = list.filter { it.active }
        val running = active.filter { it.status == DStatus.RUNNING }
        val speed = running.sumOf { it.speed }
        val first = running.firstOrNull() ?: active.firstOrNull()
        val title = if (active.size <= 1) (first?.fileName ?: "جاري التحميل") else "${active.size} تحميلات جارية"
        val b = NotificationCompat.Builder(this, Notifications.CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(if (speed > 0) "${formatBytes(speed)}/ث" else "في الانتظار…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(Notifications.openAppIntent(this, 1))
        val f = first?.fraction ?: -1f
        if (f >= 0) b.setProgress(100, (f * 100).toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val NOTIF_ID = 42

        fun ensureRunning(c: Context) {
            runCatching { ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java)) }
        }
    }
}
