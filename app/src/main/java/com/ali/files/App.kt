package com.ali.files

import android.app.Application
import android.content.Context
import android.os.Build
import com.ali.files.download.Downloader
import com.ali.files.download.VideoExtractor
import com.ali.files.util.AppSettings
import com.ali.files.util.Notifications
import com.ali.files.vault.Vault

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // the «remove the music» worker runs in its own process and needs almost nothing from the app
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":separate")) {
            com.ali.files.library.Library.init(this)
            return
        }
        AppSettings.init(this)
        Notifications.createChannels(this)
        Vault.init(this)
        Downloader.init(this)
        VideoExtractor.init(this)
        com.ali.files.library.Library.init(this)
        com.ali.files.player.PlayerHub.init(this)
        com.ali.files.browser.SiteAllow.init(this)
        com.ali.files.browser.AdStats.init(this)
        com.ali.files.browser.AdFilter.init(this)
        runCatching { com.ali.files.update.AutoUpdate.schedule(this) }
    }
}

object BuildConfigInfo {
    fun version(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "1.0"
}
