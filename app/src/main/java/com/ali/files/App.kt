package com.ali.files

import android.app.Application
import android.content.Context
import com.ali.files.download.Downloader
import com.ali.files.download.VideoExtractor
import com.ali.files.util.AppSettings
import com.ali.files.util.Notifications
import com.ali.files.vault.Vault

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        Notifications.createChannels(this)
        Vault.init(this)
        Downloader.init(this)
        VideoExtractor.init(this)
    }
}

object BuildConfigInfo {
    fun version(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "1.0"
}
