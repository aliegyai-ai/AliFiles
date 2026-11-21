package com.ali.files.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ali.files.MainActivity
import com.ali.files.R

object Notifications {
    const val CH_ACTIVE = "downloads_active"
    const val CH_DONE = "downloads_done"

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ACTIVE, "التحميلات الجارية", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "التحميلات المكتملة", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openAppIntent(c: Context, tab: Int): PendingIntent {
        val i = Intent(c, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TAB, tab)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(c, tab, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canPost(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun notifyDone(c: Context, id: Long, title: String, text: String) {
        if (!canPost(c)) return
        val n = NotificationCompat.Builder(c, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(c, 1))
            .build()
        NotificationManagerCompat.from(c).notify((id % 100000).toInt() + 1000, n)
    }
}
