package com.ali.files.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ali.files.util.AppSettings
import java.util.concurrent.TimeUnit

/**
 * «حمّل بعدين»: a download waits (paused, with its time) and starts by itself when the time comes —
 * even if the app is closed — and, if you want, only once there's Wi-Fi.
 */
object DownloadScheduler {
    private const val WORK = "scheduled-downloads"

    /** wake up at [at] (or as soon as the conditions are met after it) */
    fun plan(c: Context, at: Long) {
        val req = OneTimeWorkRequestBuilder<Worker>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (AppSettings.scheduleWifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        // several waiting: the earliest wakes first, then it plans the next
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    class Worker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
        override suspend fun doWork(): Result {
            Downloader.init(applicationContext)
            val next = Downloader.startDue()
            if (next > 0) plan(applicationContext, next)
            return Result.success()
        }
    }
}
