package com.ali.files.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
            val auto = com.ali.files.util.AppSettings.updateMode.value != "ask"
            // automatic: no question — download quietly now, install when you leave the app (like Google Play)
            autoUpdateHidden = auto
            scope.launch {
                Updater.check(ctx.applicationContext)
                val s = Updater.state.value
                if (auto && s is com.ali.files.update.UpdateState.Available) Updater.download(ctx.applicationContext, s.info)
            }
        }
    }

    Scaffold(
        modifier = Modifier.appBackground(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // a transparent background has no text color of its own: say it, or text comes out black
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(ui.snackbar) },
        bottomBar = {
            // the browser has its own slim toolbar + menu, so the app bar only shows on the other screens
            Column {
            com.ali.files.player.MiniPlayer()
            com.ali.files.cast.Cast.device.value?.let { tv ->
                androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)) {
                    androidx.compose.foundation.layout.Row(
                        androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Cast, null, tint = MaterialTheme.colorScheme.primary)
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(8.dp))
                        Text("بيتعرض على $tv", modifier = androidx.compose.ui.Modifier.weight(1f), maxLines = 1)
                        androidx.compose.material3.IconButton(onClick = { com.ali.files.cast.Cast.toggle() }) {
                            Icon(if (com.ali.files.cast.Cast.playing.value) androidx.compose.material.icons.Icons.Filled.Pause else androidx.compose.material.icons.Icons.Filled.PlayArrow, "تشغيل")
                        }
                        androidx.compose.material3.TextButton(onClick = { com.ali.files.cast.Cast.stop() }) { Text("وقّف العرض") }
                    }
                }
            }
            if (!browser.fullscreen.value && !browser.pipMode.value && tab != UiState.TAB_BROWSER) FloatingNav(tab, activeCount) { ui.tab.intValue = it }
            }
        },
    ) { pad ->
        // back from downloads / vault / settings returns to the browser instead of closing the app
        androidx.activity.compose.BackHandler(enabled = tab != UiState.TAB_BROWSER) { ui.tab.intValue = UiState.TAB_BROWSER }
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            // a soft cross-fade between the main screens
            androidx.compose.animation.AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                        androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(220), initialScale = 0.98f)) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                },
                label = "tabs",
            ) { tab ->
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
    }

    // ---------------------------------------------- first time: «اسمك إيه؟»
    if (!com.ali.files.util.AppSettings.nameAsked.value) NameAsk(
        title = "أهلاً بيك 👋",
        hint = "اسمك إيه؟ عشان التطبيق يناديك بيه",
        initial = "",
        onSkip = { com.ali.files.util.AppSettings.setUserName("") },
    ) { com.ali.files.util.AppSettings.setUserName(it) }

    // ---------------------------------------------- downloads keep going with the screen off: ask the battery saver once
    val anyActive = downloads.any { it.active }
    LaunchedEffect(anyActive) { if (anyActive) (ctx as? android.app.Activity)?.let { com.ali.files.util.KeepAwake.askBatteryExemption(it) } }

    // ---------------------------------------------- «شوف الجديد» after an update
    var whatsNew by remember { mutableStateOf(com.ali.files.update.AutoUpdate.whatsNew(ctx)) }
    whatsNew?.let { (name, notes) ->
        androidx.compose.material3.AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), 
            onDismissRequest = { com.ali.files.update.AutoUpdate.markSeen(ctx); whatsNew = null },
            title = { Text("الجديد في $name ✨") },
            text = {
                androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text(notes, lineHeight = 22.sp)
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { com.ali.files.update.AutoUpdate.markSeen(ctx); whatsNew = null }) { Text("تمام") } },
        )
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


/** The floating bottom bar: the open section is a gold pill with its name. */
@Composable
private fun FloatingNav(tab: Int, activeDownloads: Int, onPick: (Int) -> Unit) {
    val items = listOf(
        Triple(UiState.TAB_BROWSER, "المتصفح", Icons.Filled.Public),
        Triple(UiState.TAB_DOWNLOADS, "المكتبة", Icons.Filled.VideoLibrary),
        Triple(UiState.TAB_VAULT, "الخزنة", Icons.Filled.Lock),
        Triple(UiState.TAB_SETTINGS, "الإعدادات", Icons.Filled.Settings),
    )
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = androidx.compose.ui.graphics.Color(0xFF1E1A13),
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF3A3123)),
        shadowElevation = 12.dp,
        modifier = Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth().height(66.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxSize().padding(horizontal = 6.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceAround,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            items.forEach { (id, label, icon) ->
                val on = tab == id
                val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                androidx.compose.foundation.layout.Row(
                    Modifier.height(48.dp).clip(shape)
                        .then(if (on) Modifier.background(GoldBrush, shape) else Modifier)
                        .clickable { onPick(id) }
                        .animateContentSize(androidx.compose.animation.core.spring(stiffness = 500f))
                        .padding(horizontal = if (on) 16.dp else 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    val tint = if (on) androidx.compose.ui.graphics.Color(0xFF14100A) else androidx.compose.ui.graphics.Color(0xFFA89B86)
                    BadgedBox(badge = { if (id == UiState.TAB_DOWNLOADS && activeDownloads > 0 && !on) Badge { Text("$activeDownloads") } }) {
                        Icon(icon, label, tint = tint)
                    }
                    if (on) {
                        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                        Text(label, color = tint, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}


/** Asks for a name (first start, or from settings). */
@Composable
fun NameAsk(title: String, hint: String, initial: String, onSkip: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), 
        onDismissRequest = onSkip,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true, placeholder = { Text("اسمك") })
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("تمام") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onSkip) { Text("بعدين") } },
    )
}
