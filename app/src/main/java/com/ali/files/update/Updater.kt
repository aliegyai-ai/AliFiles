package com.ali.files.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ali.files.download.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

/** What GitHub publishes next to the APK (version.json). */
@Serializable
data class UpdateInfo(val versionCode: Long, val versionName: String, val notes: String = "")

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val fraction: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks the fixed GitHub release for a newer build, downloads the APK and hands it to
 * Android's installer. Works because every build is signed with the same key.
 */
object Updater {
    private const val BASE = "https://github.com/aliegyai-ai/AliFiles/releases/download/latest/"
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** The user tapped «later» for this version — don't pop the dialog again until the next version. */
    @Volatile var dismissedCode: Long = -1

    fun installedVersion(c: Context): Pair<Long, String> {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        return p.longVersionCode to (p.versionName ?: "")
    }

    @Volatile private var lastAutoCheck = 0L

    /** At most one automatic check every 10 minutes (each time the app is opened). */
    fun shouldAutoCheck(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAutoCheck < 10 * 60_000L) return false
        lastAutoCheck = now
        return true
    }

    suspend fun check(c: Context, manual: Boolean = false) = withContext(Dispatchers.IO) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading) return@withContext
        if (!manual && s is UpdateState.Ready) return@withContext
        _state.value = UpdateState.Checking
        _state.value = try {
            val body = Downloader.client.newCall(Request.Builder().url(BASE + "version.json").header("Cache-Control", "no-cache").build())
                .execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    r.body?.string() ?: ""
                }
            val info = json.decodeFromString<UpdateInfo>(body)
            if (info.versionCode > installedVersion(c).first) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: Exception) {
            if (manual) UpdateState.Failed("مقدرتش أوصل لـ GitHub: ${e.message ?: ""}") else UpdateState.Idle
        }
    }

    suspend fun download(c: Context, info: UpdateInfo) = withContext(Dispatchers.IO) {
        val dir = File(c.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "AliFiles-${info.versionName}.apk")
        _state.value = UpdateState.Downloading(info, 0f)
        try {
            Downloader.client.newCall(Request.Builder().url(BASE + "AliFiles.apk").build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                val b = r.body ?: throw java.io.IOException("ملف فاضي")
                val total = b.contentLength()
                var done = 0L
                var last = 0L
                b.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - last > 256 * 1024) {
                                last = done
                                _state.value = UpdateState.Downloading(info, done.toFloat() / total)
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw java.io.IOException("التحميل اتقطع")
            }
            _state.value = UpdateState.Ready(info, out)
        } catch (e: Exception) {
            out.delete()
            _state.value = UpdateState.Failed("التحميل فشل: ${e.message ?: ""}")
        }
    }

    /** Android needs a one-time «allow installing from this app» before it opens the installer. */
    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(c: Context) {
        c.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", apk)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun reset() { _state.value = UpdateState.Idle }
}
