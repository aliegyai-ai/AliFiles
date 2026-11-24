package com.ali.files.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ali.files.R
import com.ali.files.util.AppSettings
import com.ali.files.util.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Updates like Google Play: looks for a new version in the background, downloads it, and installs it
 * on its own when you're not using the app. Android lets an app update itself silently once it was
 * the one that installed itself — so only the very first update asks you to tap «Install».
 */
object AutoUpdate {
    private const val WORK = "auto-update"
    const val ACTION_RESULT = "com.ali.files.UPDATE_RESULT"
    private const val NOTIF = 77

    fun schedule(c: Context) {
        val wifiOnly = AppSettings.updateWifiOnly.value
        val req = PeriodicWorkRequestBuilder<Worker>(4, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** You just left the app: if an update is waiting, install it in a few seconds. */
    fun installOnLeave(c: Context) {
        if (AppSettings.updateMode.value != "auto") return
        val s = Updater.state.value
        if (s !is UpdateState.Ready && s !is UpdateState.Available) return
        WorkManager.getInstance(c).enqueueUniqueWork(
            "update-now", androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<Worker>().setInitialDelay(4, TimeUnit.SECONDS).build(),
        )
    }

    class Worker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
        override suspend fun doWork(): Result {
            val app = applicationContext
            if (AppSettings.updateMode.value == "ask") {
                Updater.check(app)
                (Updater.state.value as? UpdateState.Available)?.let { notifyAvailable(app, it.info) }
                return Result.success()
            }
            if (Updater.state.value !is UpdateState.Ready) Updater.check(app)
            val s = Updater.state.value
            if (s is UpdateState.Available) Updater.download(app, s.info)
            val ready = Updater.state.value as? UpdateState.Ready ?: return Result.success()
            if (AppSettings.updateMode.value == "auto" && quietMoment()) install(app, ready.apk, ready.info, background = true)
            else notifyReady(app, ready.info)
            return Result.success()
        }
    }

    /** not on screen, nothing downloading, nothing playing */
    private fun quietMoment(): Boolean {
        val visible = runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)
        val busy = com.ali.files.download.Downloader.items.value.any { it.active } || com.ali.files.torrent.TorrentEngine.active
        return !visible && !busy
    }

    /**
     * Installs through Android's package installer (not «open the APK»), so the app becomes its own
     * installer and the next updates don't need a tap.
     */
    suspend fun install(c: Context, apk: File, info: UpdateInfo, background: Boolean) = withContext(Dispatchers.IO) {
        saveNotes(c, info)
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setSize(apk.length())
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out, 256 * 1024) }; s.fsync(out) }
            val result = Intent(c, ResultReceiver::class.java).setAction(ACTION_RESULT).putExtra("background", background)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(c, id, result, flags).intentSender)
        }
    }

    /** Android's answer: done, or «the user has to confirm» (first time only). */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val visible = runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)
                    if (visible) runCatching { c.startActivity(confirm) }
                    else notify(
                        c, "التحديث جاهز", "اضغط عشان تثبّته (المرة دي بس — بعد كده هيتحدّث لوحده)",
                        PendingIntent.getActivity(c, 5, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                    )
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> {
                    val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    if (!i.getBooleanExtra("background", false)) android.widget.Toast.makeText(c, "التثبيت ما تمّش $msg", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** After an update: «اتحدّث لنسخة X — شوف الجديد». */
    class UpdatedReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val v = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: return
            notify(c, "ملفات علي اتحدّث لنسخة $v ✨", "اضغط وشوف الجديد", Notifications.openAppIntent(c, 0))
            schedule(c)
        }
    }

    // ------------------------------------------------------------ «شوف الجديد»

    private fun prefs(c: Context) = c.getSharedPreferences("update", Context.MODE_PRIVATE)

    private fun saveNotes(c: Context, info: UpdateInfo) =
        prefs(c).edit().putString("notes_${info.versionCode}", info.notes).putString("name_${info.versionCode}", info.versionName).apply()

    /** What's new in the version that's installed now — once. */
    fun whatsNew(c: Context): Pair<String, String>? {
        val code = Updater.installedVersion(c).first
        val p = prefs(c)
        if (p.getLong("seen", 0L) >= code) return null
        val notes = p.getString("notes_$code", null)
        if (notes == null) { p.edit().putLong("seen", code).apply(); return null }
        return (p.getString("name_$code", "") ?: "") to notes
    }

    fun markSeen(c: Context) = prefs(c).edit().putLong("seen", Updater.installedVersion(c).first).apply()

    // ------------------------------------------------------------ notifications

    private fun notifyAvailable(c: Context, info: UpdateInfo) =
        notify(c, "فيه تحديث جديد: ${info.versionName}", "افتح التطبيق عشان تحدّثه", Notifications.openAppIntent(c, 3))

    private fun notifyReady(c: Context, info: UpdateInfo) =
        notify(c, "تحديث ${info.versionName} جاهز", "هيتثبت لوحده أول ما تخرج من التطبيق، أو افتحه وثبّته دلوقتي", Notifications.openAppIntent(c, 3))

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(c: Context, title: String, text: String, intent: PendingIntent) {
        if (!Notifications.canPost(c)) return
        val n = NotificationCompat.Builder(c, Notifications.CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent).setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(c).notify(NOTIF, n)
    }
}
