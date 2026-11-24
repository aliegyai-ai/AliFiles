package com.ali.files.torrent

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

/** Keeps torrents downloading when you leave the app, with their progress in the notification. */
class TorrentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    private val awake by lazy { com.ali.files.util.KeepAwake(this, "torrents") }

    override fun onCreate() {
        super.onCreate()
        goForeground(build(TorrentEngine.items.value))
        awake.on()
        scope.launch {
            delay(3000)   // let a just-added torrent reach the list
            TorrentEngine.items.collect { list ->
                if (list.none { !it.rec.done && !it.rec.paused }) {
                    ServiceCompat.stopForeground(this@TorrentService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else post(build(list))
                delay(1500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(build(TorrentEngine.items.value))
        return START_NOT_STICKY
    }

    override fun onDestroy() { awake.off(); scope.cancel(); super.onDestroy() }

    private fun goForeground(n: Notification) =
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    @SuppressLint("MissingPermission")
    private fun post(n: Notification) {
        if (Notifications.canPost(this)) NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
    }

    private fun build(list: List<TorrentItem>): Notification {
        val active = list.filter { !it.rec.done && !it.rec.paused }
        val speed = active.sumOf { it.down }
        val first = active.firstOrNull()
        val b = NotificationCompat.Builder(this, Notifications.CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (active.size <= 1) (first?.rec?.name ?: "تورنت") else "${active.size} تورنت شغالين")
            .setContentText(
                when {
                    TorrentEngine.waitingWifi.value -> "مستني واي فاي"
                    speed > 0 -> "${formatBytes(speed.toLong())}/ث"
                    else -> first?.state ?: "بيبدأ…"
                },
            )
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(Notifications.openAppIntent(this, 1))
        val p = first?.progress ?: 0f
        if (p > 0f) b.setProgress(100, (p * 100).toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val NOTIF_ID = 43
        fun ensureRunning(c: Context) {
            runCatching { ContextCompat.startForegroundService(c, Intent(c, TorrentService::class.java)) }
        }
    }
}
