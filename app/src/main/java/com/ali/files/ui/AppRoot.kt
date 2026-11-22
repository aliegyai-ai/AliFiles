package com.ali.files.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import android.webkit.URLUtil
import com.ali.files.browser.BrowserController
import com.ali.files.browser.BrowserScreen
import com.ali.files.browser.MediaSniffer
import com.ali.files.download.DownloadDialog
import com.ali.files.download.DownloadRequest
import com.ali.files.download.Downloader
import com.ali.files.download.DownloadsScreen
import com.ali.files.download.VideoDialog
import com.ali.files.download.VideoExtractor
import com.ali.files.download.VideoRequest
import com.ali.files.vault.ImportSharedDialog
import com.ali.files.vault.VaultScreen
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ali.files.update.UpdateDialog
import com.ali.files.update.Updater

@Composable
fun AppRoot(browser: BrowserController, ui: UiState) {
    val scope = rememberCoroutineScope()
    val downloads by Downloader.items.collectAsState()
    val activeCount = downloads.count { it.active }
    val tab = ui.tab.intValue
    val ctx = LocalContext.current
    var autoUpdateHidden by remember { mutableStateOf(false) }
    // Look for a newer version every time the app comes to the screen — not only on a cold start,
    // because Android usually keeps the app alive in the background and just brings it back.
    // «remove the music» runs in another process: read its progress (and restart it if Android stopped it)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ali.files.separate.Separator.poll(ctx.applicationContext) }
            kotlinx.coroutines.delay(1000)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (Updater.shouldAutoCheck()) {
            autoUpdateHidden = false
            scope.launch { Updater.check(ctx.applicationContext) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ui.snackbar) },
        bottomBar = {
            // the browser has its own slim toolbar + menu, so the app bar only shows on the other screens
            Column {
            com.ali.files.player.MiniPlayer()
            if (!browser.fullscreen.value && tab != UiState.TAB_BROWSER) NavigationBar {
                NavigationBarItem(
                    selected = tab == UiState.TAB_BROWSER, onClick = { ui.tab.intValue = UiState.TAB_BROWSER },
                    icon = { Icon(Icons.Filled.Public, null) }, label = { Text("المتصفح") },
                )
                NavigationBarItem(
                    selected = tab == UiState.TAB_DOWNLOADS, onClick = { ui.tab.intValue = UiState.TAB_DOWNLOADS },
                    icon = {
                        BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) { Icon(Icons.Filled.VideoLibrary, null) }
                    },
                    label = { Text("المكتبة") },
                )
                NavigationBarItem(
                    selected = tab == UiState.TAB_VAULT, onClick = { ui.tab.intValue = UiState.TAB_VAULT },
                    icon = { Icon(Icons.Filled.Lock, null) }, label = { Text("الخزنة") },
                )
                NavigationBarItem(
                    selected = tab == UiState.TAB_SETTINGS, onClick = { ui.tab.intValue = UiState.TAB_SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("الإعدادات") },
                )
            }
            }
        },
    ) { pad ->
        // back from downloads / vault / settings returns to the browser instead of closing the app
        androidx.activity.compose.BackHandler(enabled = tab != UiState.TAB_BROWSER) { ui.tab.intValue = UiState.TAB_BROWSER }
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            when (tab) {
                UiState.TAB_BROWSER -> BrowserScreen(browser, ui)
                UiState.TAB_DOWNLOADS -> com.ali.files.library.LibraryTab(
                    onRequest = { r ->
                        if (MediaSniffer.kind(r.url) == null && !Downloader.isHlsUrl(r.url, null)) ui.videoRequest.value = VideoRequest(r.url)
                        else ui.pendingDownload.value = r
                    },
                    onOpenVault = { ui.tab.intValue = UiState.TAB_VAULT },
                )
                UiState.TAB_VAULT -> VaultScreen()
                else -> SettingsScreen(browser, ui)
            }
        }
    }

    // ---------------------------------------------- torrents: pick the files
    com.ali.files.torrent.TorrentInbox.pending.value?.let { (src, priv) ->
        com.ali.files.torrent.TorrentAddSheet(
            source = src, private = priv,
            onDismiss = { com.ali.files.torrent.TorrentInbox.pending.value = null },
            onStarted = {
                com.ali.files.torrent.TorrentInbox.pending.value = null
                com.ali.files.torrent.TorrentInbox.showTorrents.value = true
                com.ali.files.library.LibraryTabState.page.intValue = 1
                ui.tab.intValue = UiState.TAB_DOWNLOADS
            },
        )
    }

    // ---------------------------------------------- download confirmation
    ui.pendingDownload.value?.let { req ->
        DownloadDialog(
            req = req,
            onDismiss = { ui.pendingDownload.value = null },
            onStarted = { toVault ->
                ui.pendingDownload.value = null
                scope.launch {
                    val r = ui.snackbar.showSnackbar(
                        if (toVault) "بدأ التحميل للخزنة 🔒" else "بدأ التحميل",
                        actionLabel = "عرض", duration = SnackbarDuration.Short,
                    )
                    if (r == SnackbarResult.ActionPerformed) ui.tab.intValue = UiState.TAB_DOWNLOADS
                }
            },
        )
    }

    // ---------------------------------------------- video page (YouTube, Facebook, TikTok…)
    ui.videoRequest.value?.let { vr ->
        VideoDialog(
            req = vr,
            onDismiss = { ui.videoRequest.value = null },
            onStarted = { toVault ->
                ui.videoRequest.value = null
                ui.tab.intValue = UiState.TAB_DOWNLOADS
                scope.launch { ui.snackbar.showSnackbar(if (toVault) "بدأ تحميل الفيديو للخزنة 🔒" else "بدأ تحميل الفيديو") }
            },
            onFallback = { r -> ui.videoRequest.value = null; ui.pendingDownload.value = r },
        )
    }

    // ---------------------------------------------- app update (automatic at start, or from settings)
    if (ui.updateManual.value || !autoUpdateHidden) {
        UpdateDialog(manual = ui.updateManual.value) { ui.updateManual.value = false; autoUpdateHidden = true }
    }

    // ---------------------------------------------- files shared into the app
    if (ui.sharedUris.value.isNotEmpty()) {
        ImportSharedDialog(ui.sharedUris.value) { ui.sharedUris.value = emptyList() }
    }

    // ---------------------------------------------- link found in clipboard
    val clip = ui.clipLink.value
    LaunchedEffect(clip) {
        if (clip != null) {
            val direct = MediaSniffer.kind(clip) != null
            val video = VideoExtractor.isVideoPage(clip)
            if (video) VideoExtractor.prefetch(VideoRequest(clip))  // start reading it while the snackbar shows
            val r = ui.snackbar.showSnackbar(
                "لقيت لينك منسوخ: ${clip.take(48)}",
                actionLabel = if (direct || video) "حمّله" else "افتحه",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            ui.clipLink.value = null
            if (r == SnackbarResult.ActionPerformed) {
                if (video) {
                    ui.videoRequest.value = VideoRequest(clip)
                } else if (direct) {
                    ui.pendingDownload.value = DownloadRequest(clip, URLUtil.guessFileName(clip, null, null))
                } else {
                    browser.newTab(clip)
                    ui.tab.intValue = UiState.TAB_BROWSER
                }
            }
        }
    }

    // ---------------------------------------------- players (on top of everything)
    when {
        com.ali.files.player.PlayerHub.inPip.value -> Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            com.ali.files.player.VideoSurface(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, Modifier.fillMaxSize())
        }
        com.ali.files.player.PlayerHub.videoOpen.value -> com.ali.files.player.VideoPlayerScreen(onPip = { (ctx as? com.ali.files.MainActivity)?.enterPip() })
        com.ali.files.player.PlayerHub.musicOpen.value -> com.ali.files.player.NowPlayingScreen()
    }
}
