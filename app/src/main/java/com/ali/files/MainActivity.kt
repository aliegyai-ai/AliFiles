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
    private var permissionDone: ((Boolean) -> Unit)? = null
    private val sitePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permissionDone?.invoke(r.values.any { it }); permissionDone = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        browser = BrowserController(this).apply {
            onDownloadRequest = { ui.pendingDownload.value = it }
            onVideoRequest = { ui.videoRequest.value = it }
            onLongPress = { ui.longPress.value = it }
            launchFileChooser = { intent -> Vault.allowLeave = true; fileChooser.launch(intent) }
            requestAndroidPermissions = { perms, done -> Vault.allowLeave = true; permissionDone = done; sitePermissions.launch(perms) }
        }

        // keep the throw-away caches small
        Thread {
            if (com.ali.files.util.StorageInfo.autoClean(applicationContext)) runOnUiThread { browser.clearWebCache() }
        }.start()

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
        // a video stops when you leave the app, unless «keep sound in background» is on or it's in picture-in-picture
        if (com.ali.files.player.isVideoShowing() && !isInPictureInPictureMode && !com.ali.files.player.PlayerHub.videoBackground.value) {
            com.ali.files.player.PlayerHub.player.pause()
        }
        com.ali.files.player.PlayerHub.savePosition()
        browser.saveSession()
        if (!isChangingConfigurations) Vault.onAppStop()
    }

    /** Small floating video window while using other apps. */
    fun enterPip() {
        val vs = com.ali.files.player.PlayerHub.player.videoSize
        val w = if (vs.width > 0) vs.width else 16
        val h = if (vs.height > 0) vs.height else 9
        val ratio = (w.toFloat() / h).coerceIn(0.42f, 2.38f)
        val r = if (w >= h) android.util.Rational((ratio * 1000).toInt(), 1000) else android.util.Rational(1000, (1000 / ratio).toInt())
        runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(r).build()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // pressing Home during a playing video → it keeps going in a small window
        if (com.ali.files.player.isVideoShowing() && com.ali.files.player.PlayerHub.isPlaying.value && !com.ali.files.player.PlayerHub.videoBackground.value) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.ali.files.player.PlayerHub.inPip.value = isInPictureInPictureMode
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
                val type = intent.type ?: intent.data?.let { runCatching { contentResolver.getType(it) }.getOrNull() } ?: ""
                if (!data.startsWith("http") && (type.startsWith("video/") || type.startsWith("audio/"))) {
                    // «Open with حمّل» from another app → play it here
                    val u = intent.data ?: return
                    val name = com.ali.files.util.FileStore.displayName(this, u)
                    com.ali.files.player.PlayerHub.play(listOf(com.ali.files.player.Track(
                        id = data, uri = u, title = name.substringBeforeLast('.'), subtitle = "", isVideo = type.startsWith("video/"),
                    )))
                    return
                }
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
