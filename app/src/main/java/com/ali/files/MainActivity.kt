package com.ali.files

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.webkit.URLUtil
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.ali.files.browser.BrowserController
import com.ali.files.browser.MediaSniffer
import com.ali.files.download.DownloadRequest
import com.ali.files.download.VideoExtractor
import com.ali.files.download.VideoRequest
import com.ali.files.ui.AppRoot
import com.ali.files.ui.AppTheme
import com.ali.files.ui.UiState
import com.ali.files.util.AppSettings
import com.ali.files.util.Notifications
import com.ali.files.vault.Vault

class MainActivity : FragmentActivity() {
    lateinit var browser: BrowserController
    val ui = UiState()

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        browser.onFileChosen(it.resultCode, it.data)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        browser = BrowserController(this).apply {
            onDownloadRequest = { ui.pendingDownload.value = it }
            onVideoRequest = { ui.videoRequest.value = it }
            onLongPress = { ui.longPress.value = it }
            launchFileChooser = { intent -> Vault.allowLeave = true; fileChooser.launch(intent) }
        }

        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot(browser, ui)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(this)) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Vault.onAppStart()
    }

    override fun onStop() {
        super.onStop()
        browser.saveSession()
        if (!isChangingConfigurations) Vault.onAppStop()
    }

    override fun onDestroy() {
        if (::browser.isInitialized) browser.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && AppSettings.clipboardDetect.value) checkClipboard()
    }

    private fun checkClipboard() {
        val text = runCatching {
            getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        }.getOrNull() ?: return
        val url = findUrl(text) ?: return
        if (url == AppSettings.lastClip) return
        if (url == browser.current?.url?.value) return
        AppSettings.lastClip = url
        ui.clipLink.value = url
    }

    private fun findUrl(text: String): String? {
        val m = Patterns.WEB_URL.matcher(text)
        while (m.find()) {
            val u = m.group()
            if (u.startsWith("http://") || u.startsWith("https://")) return u
        }
        return null
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getIntExtra(EXTRA_TAB, -1).takeIf { it >= 0 }?.let { ui.tab.intValue = it }
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.dataString ?: return
                if (data.startsWith("http")) {
                    browser.newTab(data)
                    ui.tab.intValue = UiState.TAB_BROWSER
                }
            }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (stream != null) {
                    ui.sharedUris.value = listOf(stream)
                    ui.tab.intValue = UiState.TAB_VAULT
                } else if (text != null) {
                    val url = findUrl(text) ?: return
                    AppSettings.lastClip = url
                    if (VideoExtractor.isVideoPage(url)) {
                        ui.videoRequest.value = VideoRequest(url)
                        ui.tab.intValue = UiState.TAB_DOWNLOADS
                    } else if (MediaSniffer.kind(url) != null) {
                        ui.pendingDownload.value = DownloadRequest(url, URLUtil.guessFileName(url, null, null))
                        ui.tab.intValue = UiState.TAB_DOWNLOADS
                    } else {
                        browser.newTab(url)
                        ui.tab.intValue = UiState.TAB_BROWSER
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!list.isNullOrEmpty()) {
                    ui.sharedUris.value = list.toList()
                    ui.tab.intValue = UiState.TAB_VAULT
                }
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
