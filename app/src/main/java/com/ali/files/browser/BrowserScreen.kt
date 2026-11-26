@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ali.files.browser

import com.ali.files.ui.sheetBody
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.MusicNote
import com.ali.files.library.toTrack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import android.view.ViewGroup
import android.webkit.URLUtil
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ali.files.download.DStatus
import com.ali.files.download.DownloadRequest
import com.ali.files.download.Downloader
import com.ali.files.download.VideoExtractor
import com.ali.files.download.VideoRequest
import com.ali.files.ui.UiState
import com.ali.files.util.AppSettings
import com.ali.files.util.formatBytes
import java.util.Calendar
import kotlinx.coroutines.launch

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", text))
    Toast.makeText(ctx, "اتنسخ", Toast.LENGTH_SHORT).show()
}

/** A link copied to the clipboard, if there is one (Android only lets the app read it while it's on screen). */
private fun clipboardLink(ctx: Context): String? = runCatching {
    val t = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
    t?.takeIf { it.startsWith("http") && !it.contains(' ') && it.length < 2000 }
}.getOrNull()

private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.").removePrefix("m.")

@Composable
fun BrowserScreen(c: BrowserController, ui: UiState) {
    val ctx = LocalContext.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { c.awayOnPurpose = false }
    BrowserContent(c, ui)
}

@Composable
private fun BrowserContent(c: BrowserController, ui: UiState) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val tab = c.current
    var showTabs by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showAds by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var lastBackOnHome by remember { mutableLongStateOf(0L) }
    val home = tab?.home?.value == true

    // One back handler that always decides itself, so a swipe-back never closes the app by surprise:
    // previous page → start page → «swipe again to exit».
    BackHandler(enabled = !c.fullscreen.value) {
        val t = c.current
        when {
            findOpen -> { findOpen = false; t?.webView?.clearMatches() }
            t == null -> {}
            !t.home.value -> c.back(t)
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackOnHome < 2000) (ctx as? android.app.Activity)?.moveTaskToBack(true)
                else {
                    lastBackOnHome = now
                    Toast.makeText(ctx, "اسحب تاني للخروج", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    BackHandler(enabled = c.fullscreen.value) { c.exitFullscreen() }

    // While typing in a page (e.g. YouTube search) the toolbar hides and stays hidden: showing/hiding it
    // resized the page under the keyboard, and sites like YouTube then drew their filter row over the suggestions.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { c.imeOpen = imeVisible }
    val typingInPage = imeVisible && !c.addressFocused.value && !findOpen
    val barsVisible = !c.pipMode.value && !typingInPage && (home || !c.barsHidden.value || findOpen)

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------- page (or start page)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (home) {
                StartPage(c, ui)
                // a site is opening from here: show its progress until it appears
                if (tab?.openingFromHome?.value == true) LinearProgressIndicator(
                    progress = { (tab.progress.intValue / 100f).coerceAtLeast(0.05f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                )
            } else {
                // each tab's page sits in its own pull-to-refresh frame
                AndroidView(
                    factory = { context -> FrameLayout(context) },
                    modifier = Modifier.fillMaxSize(),
                    update = { container ->
                        val f = tab?.frame
                        if (f != null && (container.childCount != 1 || container.getChildAt(0) !== f)) {
                            container.removeAllViews()
                            (f.parent as? ViewGroup)?.removeView(f)
                            container.addView(f, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                    },
                )
                // optional floating download button (off by default — the toolbar icon does the same job)
                val videoSite = tab?.url?.value?.let { VideoExtractor.isVideoPage(it) } == true
                val found = tab?.videoCount ?: 0
                if (AppSettings.floatingDownload.value && tab != null && (tab.playing.value || videoSite || found > 0)) {
                    ExtendedFloatingActionButton(
                        onClick = { downloadAction(c, tab) { showMedia = true } },
                        icon = { Icon(Icons.Filled.Download, null) },
                        text = { Text("حمّل الفيديو", fontWeight = FontWeight.Bold) },
                        shape = RoundedCornerShape(50),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                    )
                }
                // the site covered itself with a «log in» window → one tap to try without an account
                if (tab != null && tab.loginWall.value && LoginWall.isWallSite(tab.url.value)) {
                    ExtendedFloatingActionButton(
                        onClick = { c.tryWithoutLogin(tab) },
                        icon = { Icon(Icons.Filled.LockOpen, null) },
                        text = { Text("جرّب من غير تسجيل", fontWeight = FontWeight.Bold) },
                        shape = RoundedCornerShape(50),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
                    )
                }
                // thin loading line at the bottom edge of the page
                val progress = tab?.progress?.intValue ?: 100
                if (progress < 100) LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                )
            }
        }

        if (findOpen) FindBar(
            result = tab?.findResult?.value ?: (0 to 0),
            query = findQuery,
            onQuery = { findQuery = it; tab?.webView?.findAllAsync(it) },
            onNext = { tab?.webView?.findNext(true) },
            onPrev = { tab?.webView?.findNext(false) },
            onClose = { findOpen = false; tab?.webView?.clearMatches() },
        )

        // ---------------------------------------------------- one slim toolbar (slides away while scrolling down)
        AnimatedVisibility(visible = barsVisible, enter = expandVertically(), exit = shrinkVertically()) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF1E1A13),
                border = BorderStroke(1.dp, Color(0xFF3A3123)),
                shadowElevation = 14.dp,
                modifier = Modifier.navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val canBack = if (home) tab?.url?.value?.isNotBlank() == true else true
                    BarButton(enabled = canBack, onClick = {
                        val t = c.current ?: return@BarButton
                        if (t.home.value) c.leaveHome(t) else c.back(t)
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", Modifier.size(22.dp)) }

                    AddressField(c, tab, Modifier.weight(1f).padding(horizontal = 4.dp)) { focus.clearFocus() }

                    // download: grey when nothing to download, gold when the page has a video
                    val videoSite = !home && tab?.url?.value?.let { VideoExtractor.isVideoPage(it) } == true
                    val found = if (home) 0 else tab?.videoCount ?: 0
                    // gold only when there's really a video to take: an open video page (not a feed with previews)
                    val singleVideo = !home && tab?.url?.value?.let { VideoExtractor.isSingleVideoPage(it) } == true
                    val hasVideo = singleVideo || found > 0 || (!home && !videoSite && tab?.playing?.value == true)
                    // a video on the page: a gold «حمّل» pill right in the bar
                    androidx.compose.animation.AnimatedVisibility(hasVideo, enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
                        Row(
                            Modifier.padding(horizontal = 2.dp).height(42.dp).clip(RoundedCornerShape(14.dp)).background(com.ali.files.ui.GoldBrush)
                                .clickable { tab?.let { t -> downloadAction(c, t) { showMedia = true } } }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Download, null, Modifier.size(20.dp), tint = Color(0xFF14100A))
                            Spacer(Modifier.width(4.dp))
                            Text(if (found > 1) "حمّل ($found)" else "حمّل", color = Color(0xFF14100A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    if (!hasVideo) BarButton(enabled = !home && tab != null, onClick = { tab?.let { t -> downloadAction(c, t) { showMedia = true } } }) {
                        BadgedBox(badge = {
                            if (found > 1) Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                Text(if (found > 9) "+9" else "$found", fontSize = 9.sp)
                            }
                        }) {
                            Icon(
                                Icons.Filled.Download, "حمّل الفيديو", Modifier.size(22.dp),
                                tint = when {
                                    home -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    hasVideo -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }

                    BarButton(onClick = { tab?.let { c.captureThumb(it) }; showTabs = true }) {
                        Box(
                            Modifier.size(22.dp).border(1.6.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val n = c.tabs.size
                            Text(if (n > 99) "∞" else "$n", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    BarButton(onClick = { menu = true }) { Icon(Icons.Filled.Menu, "القائمة", Modifier.size(22.dp)) }
                }
            }
        }
    }

    // ---------------------------------------------------- sheets & dialogs
    if (menu) MenuSheet(
        c = c, tab = tab, home = home,
        onDismiss = { menu = false },
        onFind = { findOpen = true },
        onLibrary = { showLibrary = true },
        onMedia = { tab?.let { c.sniffDom(it) }; showMedia = true },
        onOpenApp = { ui.tab.intValue = it },
        onAds = { showAds = true },
    )
    if (showAds) AdSheet(tab?.takeIf { !home }) { showAds = false }
    if (showTabs) TabsSheet(c) { showTabs = false }
    if (showMedia && tab != null) MediaSheet(c, tab) { showMedia = false }
    if (showLibrary) LibrarySheet(c) { showLibrary = false }

    c.sitePermission.value?.let { sp ->
        val what = sp.kinds.joinToString(" و") { when (it) { "camera" -> "الكاميرا"; "mic" -> "المايك"; else -> "مكانك" } }
        AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), 
            onDismissRequest = { sp.answer(false) },
            title = { Text("$what؟") },
            text = { Text("موقع ${sp.host} عايز يستخدم $what.") },
            confirmButton = { TextButton(onClick = { sp.answer(true) }) { Text("اسمح", fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { sp.answer(false) }) { Text("لأ") } },
        )
    }

    ui.longPress.value?.let { lp ->
        AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), 
            onDismissRequest = { ui.longPress.value = null },
            title = { Text(if (lp.isImage) "صورة" else "رابط", maxLines = 1) },
            text = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(lp.url, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { ui.longPress.value = null; c.newTab(lp.url) }) { Text("افتح في تبويب جديد") }
                    TextButton(onClick = { ui.longPress.value = null; c.requestDownload(lp.url, c.current) }) { Text(if (lp.isImage) "حمّل الصورة" else "حمّل الرابط") }
                    if (!lp.isImage && VideoExtractor.isSingleVideoPage(lp.url)) {
                        TextButton(onClick = { ui.longPress.value = null; ui.videoRequest.value = VideoRequest(lp.url) }) { Text("حمّل الفيديو اللي في اللينك ده") }
                    }
                    TextButton(onClick = {
                        ui.longPress.value = null
                        c.awayOnPurpose = true; ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lp.url), "مشاركة"))
                    }) { Text("شارك الرابط") }
                    TextButton(onClick = { ui.longPress.value = null; copy(ctx, lp.url) }) { Text("انسخ الرابط") }
                }
            },
        )
    }
}

/** A compact 44dp toolbar button (the default IconButton is 48dp and made the bar too tall). */
@Composable
private fun BarButton(enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier else Modifier.alpha(0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { content() }
    }
}

// ============================================================ ad blocker sheet

@Composable
private fun AdSheet(tab: BrowserTab?, onDismiss: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val host = tab?.pageHost?.removePrefix("www.")
    var siteAllowed by remember { mutableStateOf(host?.let { SiteAllow.isAllowed(it) } ?: false) }
    val on = AppSettings.adBlock.value
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = scheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().sheetBody().padding(horizontal = 18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(scheme.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Shield, null, tint = scheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("مانع الإعلانات", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(if (on) "شغّال" else "مقفول", color = if (on) scheme.primary else scheme.onSurfaceVariant, fontSize = 13.sp)
                }
                androidx.compose.material3.Switch(checked = on, onCheckedChange = { AppSettings.setAdBlock(it) })
            }

            // counters
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("في الصفحة دي", "${tab?.blocked?.intValue ?: 0}", Modifier.weight(1f))
                StatBox("من أول ما سطّبت", "%,d".format(AdStats.total.longValue), Modifier.weight(1f))
            }

            if (host != null && on) {
                Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("امنع الإعلانات في $host", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("اقفله لو الموقع ده باظ أو رفض يشتغل", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(checked = !siteAllowed, onCheckedChange = { block ->
                            SiteAllow.set(host, !block); siteAllowed = !block
                            tab?.webView?.reload()
                        })
                    }
                }
            }

            val days = if (AdFilter.lastUpdate.longValue > 0) ((System.currentTimeMillis() - AdFilter.lastUpdate.longValue) / 86_400_000L) else -1L
            Text(
                "%,d قاعدة من EasyList وEasyPrivacy والقايمة العربية".format(AdFilter.ruleCount.intValue) + "\n" +
                    when { days < 0 -> "القوايم اللي جوه التطبيق (بتتحدث لوحدها كل أسبوع)"; days == 0L -> "اتحدثت النهارده"; else -> "اتحدثت من $days يوم" },
                fontSize = 12.sp, color = scheme.onSurfaceVariant,
            )
            OutlinedButton(
                enabled = !AdFilter.updating.value,
                onClick = { scope.launch { AdFilter.update() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp))
                Text(if (AdFilter.updating.value) "بيحدّث القوايم…" else "حدّث القوايم دلوقتي")
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================ menu (grid of icons in a bottom sheet)

@Composable
private fun MenuSheet(
    c: BrowserController, tab: BrowserTab?, home: Boolean,
    onDismiss: () -> Unit, onFind: () -> Unit, onLibrary: () -> Unit, onMedia: () -> Unit, onOpenApp: (Int) -> Unit,
    onAds: () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val page = tab?.takeIf { !home }
    fun act(f: () -> Unit) { onDismiss(); f() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = scheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().sheetBody().padding(horizontal = 14.dp).padding(bottom = 18.dp)) {
            // quick row
            if (page != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(enabled = page.canForward.value, onClick = { act { page.webView.goForward() } }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "قدام") }
                    IconButton(onClick = { act { page.webView.reload() } }) { Icon(Icons.Filled.Refresh, "تحديث") }
                    val marked = c.isBookmarked(page.url.value)
                    IconButton(onClick = { act { c.toggleBookmark(page) } }) {
                        Icon(if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "المفضلة", tint = if (marked) scheme.primary else scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        act { c.awayOnPurpose = true; ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, page.url.value), "مشاركة")) }
                    }) { Icon(Icons.Filled.Share, "مشاركة") }
                    IconButton(onClick = { act { copy(ctx, page.url.value) } }) { Icon(Icons.Filled.ContentCopy, "نسخ الرابط") }
                }
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(bottom = 12.dp))
            }

            val items = buildList {
                add(MenuItem("تبويب جديد", Icons.Filled.Add) { act { c.newTab() } })
                add(MenuItem("المفضلة والسجل", Icons.Filled.History) { act(onLibrary) })
                add(MenuItem("الرئيسية", Icons.Filled.Home) { act { c.goHome() } })
                add(MenuItem("الإعلانات", Icons.Filled.Shield, selected = AppSettings.adBlock.value && (page?.pageHost?.let { !SiteAllow.isAllowed(it) } ?: true)) { act(onAds) })
                if (page != null) {
                    add(MenuItem("بحث في الصفحة", Icons.Filled.Search) { act(onFind) })
                    add(MenuItem("وسائط الصفحة", Icons.Filled.VideoLibrary) { act(onMedia) })
                    add(MenuItem("وضع الكمبيوتر", Icons.Filled.Computer, selected = page.desktop.value) { act { c.setDesktop(page, !page.desktop.value) } })
                    if (page.playing.value) {
                        add(MenuItem("صورة داخل صورة", Icons.Filled.PictureInPictureAlt) { act { (ctx as? com.ali.files.MainActivity)?.enterBrowserPip() } })
                        add(MenuItem("كمّل في الخلفية", Icons.Filled.Headphones) {
                            act { (ctx as? android.app.Activity)?.moveTaskToBack(true) }
                        })
                    }
                    page.pageHost?.let { host ->
                        add(MenuItem("انسى الموقع ده", Icons.Filled.DeleteSweep) {
                            act { ForgetSites.forget(host); page.webView?.reload(); Toast.makeText(ctx, "${ForgetSites.siteOf(host)} نسي زيارتك", Toast.LENGTH_SHORT).show() }
                        })
                        if (AppSettings.forgetMode.value) {
                            val kept = ForgetSites.isKept(host)
                            add(MenuItem(if (kept) "مش لازم يفتكرني" else "خلّيه فاكرني", Icons.Filled.Favorite, selected = kept) {
                                act {
                                    ForgetSites.toggleKeep(host)
                                    Toast.makeText(ctx, if (!kept) "${ForgetSites.siteOf(host)} هيفضل فاكرك" else "${ForgetSites.siteOf(host)} هينسى زيارتك", Toast.LENGTH_SHORT).show()
                                }
                            })
                        }
                    }
                }
                add(MenuItem("المكتبة", Icons.Filled.VideoLibrary) { act { onOpenApp(UiState.TAB_DOWNLOADS) } })
                add(MenuItem("الإعدادات", Icons.Filled.Settings) { act { onOpenApp(UiState.TAB_SETTINGS) } })
            }
            items.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    row.forEach { m -> Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { MenuTile(m) } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private class MenuItem(
    val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accent: Color? = null, val selected: Boolean = false, val onClick: () -> Unit,
)

@Composable
private fun MenuTile(m: MenuItem) {
    val scheme = MaterialTheme.colorScheme
    val tint = m.accent ?: scheme.primary
    Column(
        Modifier.width(80.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = m.onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(15.dp))
                .background(if (m.selected) tint.copy(alpha = 0.28f) else scheme.surfaceContainerHighest)
                .then(if (m.selected) Modifier.border(1.5.dp, tint, RoundedCornerShape(15.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Icon(m.icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(5.dp))
        Text(m.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/**
 * What the download button does: video sites and a playing video go to yt-dlp; a page with several
 * caught media files opens the list to pick from; anything else scans the page and opens the list.
 */
private fun downloadAction(c: BrowserController, tab: BrowserTab, openMedia: () -> Unit) {
    val url = tab.url.value
    when {
        // YouTube / Facebook / TikTok…: the video open now (downloadVideo checks it's a real video page)
        VideoExtractor.isVideoPage(url) -> c.downloadVideo(tab)
        tab.playing.value -> c.downloadVideo(tab)
        tab.videoCount == 1 -> c.requestDownload(tab.media.first { it.kind == "فيديو" || it.kind == "HLS" }.url, tab)
        else -> { c.sniffDom(tab); openMedia() }
    }
}

// ============================================================ start page

private data class QuickSite(val name: String, val url: String, val letter: String, val color: Color)

private val defaultSites = listOf(
    QuickSite("يوتيوب", "https://m.youtube.com/", "▶", Color(0xFFE5574A)),
    QuickSite("فيسبوك", "https://m.facebook.com/", "f", Color(0xFF4A8BE0)),
    QuickSite("تيك توك", "https://www.tiktok.com/", "♪", Color(0xFFE6E0F0)),
    QuickSite("انستجرام", "https://www.instagram.com/", "◎", Color(0xFFE0609A)),
    QuickSite("إكس", "https://x.com/", "X", Color(0xFFB8B0A0)),
    QuickSite("جوجل", "https://www.google.com/", "G", Color(0xFF6FB27A)),
)

private class Tile(val name: String, val letter: String, val color: Color, val dashed: Boolean = false, val onClick: () -> Unit, val onLongClick: (() -> Unit)? = null)

private val siteColors = listOf(Color(0xFFD4A94F), Color(0xFF79B0E0), Color(0xFFB896E6), Color(0xFF7FB685), Color(0xFFE88A75), Color(0xFFC9A57D))

@Composable
private fun StartPage(c: BrowserController, ui: UiState) {
    val ctx = LocalContext.current
    val downloads by Downloader.items.collectAsState()
    var query by remember { mutableStateOf("") }
    var clip by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Bookmark?>(null) }
    LaunchedEffect(Unit) {
        clip = clipboardLink(ctx)
        clip?.takeIf { VideoExtractor.isVideoPage(it) }?.let { VideoExtractor.prefetch(VideoRequest(it)) }
    }

    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val userName = AppSettings.userName.value
    val greeting = (if (hour in 4..11) "صباح الخير" else "مساء الخير") + if (userName.isNotBlank()) " يا $userName" else ""
    val gold = MaterialTheme.colorScheme.primary

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        run {
            // the app's mark and a greeting
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF221C12)), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.res.painterResource(com.ali.files.R.drawable.ic_launcher_fg), null, tint = Color.Unspecified, modifier = Modifier.size(52.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(greeting.substringBefore(" يا"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(if (userName.isNotBlank()) "يا $userName" else "أهلاً بيك", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 21.sp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // search
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, gold.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(gold),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (query.isNotBlank()) c.load(query) }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("ابحث أو الصق لينك", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    },
                )
            }
        }

        // link in the clipboard
        clip?.let { link ->
            val video = VideoExtractor.isVideoPage(link)
            val direct = MediaSniffer.kind(link) != null
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = gold.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, gold.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ContentPaste, null, tint = gold, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("لقيت لينك في الحافظة", color = gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(hostOf(link), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = {
                        clip = null
                        when {
                            video -> ui.videoRequest.value = VideoRequest(link)
                            direct -> ui.pendingDownload.value = DownloadRequest(link, URLUtil.guessFileName(link, null, null))
                            else -> c.load(link)
                        }
                    }) { Text(if (video || direct) "حمّله" else "افتحه", fontWeight = FontWeight.Bold) }
                    IconButton(onClick = { clip = null }) { Icon(Icons.Filled.Close, "إخفاء", Modifier.size(18.dp)) }
                }
            }
        }

        // your library, one tap away
        Spacer(Modifier.height(18.dp))
        HomeLibraryTiles(ui, downloads)
        ContinueRow(ui)

        // sites: the big ones + your own (long-press to remove)
        Spacer(Modifier.height(22.dp))
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val tiles = buildList {
            defaultSites.forEach { s -> add(Tile(s.name, s.letter, s.color, onClick = { c.load(s.url) })) }
            c.bookmarks.take(12).forEachIndexed { i, b ->
                add(Tile(b.title, hostOf(b.url).take(1).uppercase(), siteColors[i % siteColors.size], onClick = { c.load(b.url) }, onLongClick = { removing = b }))
            }
            add(Tile("إضافة", "+", muted, dashed = true, onClick = { adding = true }))
        }
        Text("مواقعك", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.forEach { t -> SiteTile(t.name, t.letter, t.color, t.dashed, t.onClick, t.onLongClick) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (adding) AddSiteDialog(onDismiss = { adding = false }) { name, url -> c.addBookmark(name, url); adding = false }
    removing?.let { b ->
        AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), 
            onDismissRequest = { removing = null },
            title = { Text("تشيل «${b.title}»؟") },
            text = { Text("هيتشال من الصفحة الرئيسية والمفضلة.") },
            confirmButton = { TextButton(onClick = { c.removeBookmark(b); removing = null }) { Text("شيله") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("لأ") } },
        )
    }
}

@Composable
private fun SiteTile(name: String, letter: String, color: Color, dashed: Boolean = false, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .background(if (dashed) Color.Transparent else color.copy(alpha = 0.14f))
                .border(1.dp, if (dashed) color.copy(alpha = 0.5f) else color.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 64.dp))
    }
}

/** Start page: four big tiles into the library (videos, songs, downloading now, playlists). */
@Composable
private fun HomeLibraryTiles(ui: UiState, downloads: List<com.ali.files.download.DownloadItem>) {
    val files by com.ali.files.library.Library.files.collectAsState()
    val lists by com.ali.files.library.Library.playlists.collectAsState()
    val videos = files.filter { it.kind == com.ali.files.util.FileKind.VIDEO }
    val songs = files.filter { it.kind == com.ali.files.util.FileKind.AUDIO }
    val active = downloads.filter { it.active }
    val speed = active.sumOf { it.speed }
    fun lib(filter: Int) { com.ali.files.library.LibraryTabState.open(filter); ui.tab.intValue = UiState.TAB_DOWNLOADS }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeTile("فيديوهات", "${videos.size}", formatBytes(videos.sumOf { it.size }), Icons.Filled.Movie, Color(0xFFE8B865), Modifier.weight(1f)) { lib(1) }
            HomeTile("أغاني", "${songs.size}", formatBytes(songs.sumOf { it.size }), Icons.Filled.MusicNote, Color(0xFF9CC7E8), Modifier.weight(1f)) { lib(2) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeTile(
                if (active.isEmpty()) "التحميلات" else "بيتحمّل", "${active.size}",
                if (active.isEmpty()) "مفيش حاجة شغالة" else "${formatBytes(speed)}/ث",
                Icons.Filled.Download, Color(0xFF7FB685), Modifier.weight(1f),
            ) { com.ali.files.library.LibraryTabState.page.intValue = 1; ui.tab.intValue = UiState.TAB_DOWNLOADS }
            HomeTile("قوايم التشغيل", "${lists.size}", "تشغيل بالترتيب", Icons.AutoMirrored.Filled.QueueMusic, Color(0xFFBA68C8), Modifier.weight(1f)) { lib(6) }
        }
    }
}

@Composable
private fun HomeTile(label: String, number: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest), modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(number, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** «كمّل من مكان ما وقفت»: what you played lately (or the newest files), with how far you got. */
@Composable
private fun ContinueRow(ui: UiState) {
    val files by com.ali.files.library.Library.files.collectAsState()
    LaunchedEffect(Unit) { com.ali.files.player.PlayerHub.loadRecent() }
    val recentIds = com.ali.files.player.PlayerHub.recent.value
    val byPath = files.associateBy { it.path }
    val playable = files.filter { it.kind == com.ali.files.util.FileKind.VIDEO || it.kind == com.ali.files.util.FileKind.AUDIO }
    val list = (recentIds.mapNotNull { byPath[it] } + playable).distinctBy { it.path }.take(8)
    if (list.isEmpty()) return
    Spacer(Modifier.height(22.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (recentIds.isNotEmpty()) "كمّل من مكان ما وقفت" else "آخر حاجات حمّلتها", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { com.ali.files.library.LibraryTabState.open(0); ui.tab.intValue = UiState.TAB_DOWNLOADS }) { Text("المكتبة") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        list.forEach { f -> ContinueCard(f, list) }
    }
}

@Composable
private fun ContinueCard(f: com.ali.files.library.LibFile, all: List<com.ali.files.library.LibFile>) {
    val thumb by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, f.path) { value = com.ali.files.library.Library.thumbnail(f) }
    val dur by androidx.compose.runtime.produceState(0L, f.path) { value = com.ali.files.library.Library.duration(f) }
    val progress = com.ali.files.player.PlayerHub.progressOf(f.path)
    val gold = MaterialTheme.colorScheme.primary
    Column(
        Modifier.width(150.dp).clip(RoundedCornerShape(14.dp)).clickable {
            val same = all.filter { it.kind == f.kind }
            com.ali.files.player.PlayerHub.play(same.map { it.toTrack() }, same.indexOf(f).coerceAtLeast(0))
        },
    ) {
        Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(gold.copy(alpha = 0.25f), Color(0xFF1A150E))))) {
            val b = thumb
            if (b != null) androidx.compose.foundation.Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Icon(if (f.kind == com.ali.files.util.FileKind.AUDIO) Icons.Filled.MusicNote else Icons.Filled.Movie, null, tint = gold, modifier = Modifier.align(Alignment.Center))
            if (dur > 0) Text(
                com.ali.files.player.formatTime(dur), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
            )
            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(gold))
        }
        Spacer(Modifier.height(6.dp))
        Text(f.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
    }
}

@Composable
private fun AddSiteDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), 
        onDismissRequest = onDismiss,
        title = { Text("إضافة موقع للرئيسية") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("الاسم") }, singleLine = true)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, label = { Text("اللينك") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { onAdd(name.trim(), url.trim()) }) { Text("إضافة") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

// ============================================================ address bar

@Composable
private fun AddressField(c: BrowserController, tab: BrowserTab?, modifier: Modifier, onDone: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val home = tab?.home?.value == true
    val url = if (home) "" else tab?.url?.value ?: ""
    LaunchedEffect(url, editing, tab?.id) { if (!editing) text = TextFieldValue(if (editing) url else hostOf(url).takeIf { url.isNotBlank() } ?: "") }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier.height(42.dp)) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                home -> Icon(Icons.Filled.Search, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                (tab?.blocked?.intValue ?: 0) > 0 -> BadgedBox(badge = { Badge { Text("${tab?.blocked?.intValue}", fontSize = 9.sp) } }) {
                    Icon(Icons.Filled.Shield, "مانع الإعلانات", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                }
                tab?.secure == true -> Icon(Icons.Filled.Lock, "آمن", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                else -> Icon(Icons.Filled.Public, null, Modifier.size(17.dp))
            }
            Spacer(Modifier.width(8.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { c.load(text.text); onDone() }),
                    modifier = Modifier.weight(1f).onFocusChanged { f ->
                        if (f.isFocused && !editing) {
                            // show the full address while editing, selected so typing replaces it
                            text = TextFieldValue(url, selection = TextRange(0, url.length))
                        }
                        editing = f.isFocused
                        c.addressFocused.value = f.isFocused
                    },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.text.isEmpty()) Text("ابحث أو اكتب رابط", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            inner()
                        }
                    },
                )
            }
            if (!home && !editing && tab != null) {
                val loading = tab.progress.intValue < 100
                Icon(
                    if (loading) Icons.Filled.Close else Icons.Filled.Refresh, if (loading) "إيقاف" else "تحديث",
                    Modifier.size(32.dp).clip(CircleShape).clickable { if (loading) tab.webView.stopLoading() else tab.webView.reload() }.padding(7.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FindBar(result: Pair<Int, Int>, query: String, onQuery: (String) -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("دور في الصفحة…", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } },
        )
        if (query.isNotEmpty()) Text(
            if (result.second == 0) "مفيش" else "${result.first} من ${result.second}",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp),
        )
        IconButton(onClick = onPrev) { Icon(Icons.Filled.KeyboardArrowUp, "السابق") }
        IconButton(onClick = onNext) { Icon(Icons.Filled.KeyboardArrowDown, "التالي") }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "إغلاق") }
    }
}

// ============================================================ tab switcher (grid of page pictures)

@Composable
private fun TabsSheet(c: BrowserController, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("التبويبات (${c.tabs.size})", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { c.newTab(); onDismiss() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("تبويب جديد")
            }
            OutlinedButton(onClick = { c.closeAll(); onDismiss() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Close, null); Spacer(Modifier.width(4.dp)); Text("اقفل الكل")
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(c.tabs, key = { it.id }) { t -> TabCard(t, selected = t.id == c.currentId.longValue, onOpen = { c.select(t.id); onDismiss() }, onClose = { c.closeTab(t.id) }) }
        }
    }
}


@Composable
private fun TabCard(t: BrowserTab, selected: Boolean, onOpen: () -> Unit, onClose: () -> Unit) {
    val gold = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, gold) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (t.home.value) "الصفحة الرئيسية" else t.title.value.ifBlank { hostOf(t.url.value).ifBlank { "صفحة جديدة" } },
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Close, "اقفل", Modifier.size(16.dp)) }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(0.8f).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                val thumb = t.thumb.value
                if (thumb != null && !t.home.value) {
                    Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                } else {
                    Icon(if (t.home.value) Icons.Filled.Home else Icons.Filled.Public, null, Modifier.size(34.dp), tint = gold.copy(alpha = 0.6f))
                }
            }
        }
    }
}

// ============================================================ media found in the page

@Composable
private fun MediaSheet(c: BrowserController, tab: BrowserTab, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("الوسائط اللي في الصفحة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            "شغّل الفيديو الأول لو مش ظاهر، وهيتلقط تلقائياً.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        androidx.compose.material3.Button(
            onClick = { onDismiss(); c.requestVideo(tab) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Filled.Download, null)
            Spacer(Modifier.width(8.dp))
            Text("استخرج فيديو الصفحة (يوتيوب، فيسبوك، تيك توك…)")
        }
        if (tab.media.isEmpty()) {
            Text("لسه مفيش وسائط", Modifier.padding(24.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            items(tab.media.toList(), key = { it.url }) { m ->
                ListItem(
                    overlineContent = { Text(m.kind) },
                    headlineContent = {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(m.url.substringBefore('?').substringAfterLast('/').ifBlank { m.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    supportingContent = {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(m.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                        }
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { copy(ctx, m.url) }) { Icon(Icons.Filled.ContentCopy, "نسخ") }
                            IconButton(onClick = { onDismiss(); c.requestDownload(m.url, tab) }) { Icon(Icons.Filled.Download, "تحميل", tint = MaterialTheme.colorScheme.primary) }
                        }
                    },
                )
            }
        }
    }
}

// ============================================================ bookmarks & history

@Composable
private fun LibrarySheet(c: BrowserController, onDismiss: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        TabRow(selectedTabIndex = page) {
            Tab(selected = page == 0, onClick = { page = 0 }, text = { Text("المفضلة (${c.bookmarks.size})") })
            Tab(selected = page == 1, onClick = { page = 1 }, text = { Text("السجل") })
        }
        if (page == 1 && c.history.isNotEmpty()) {
            TextButton(onClick = { c.clearHistory() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("امسح السجل") }
        }
        LazyColumn(Modifier.fillMaxWidth().height(420.dp).navigationBarsPadding()) {
            if (page == 0) {
                items(c.bookmarks.toList(), key = { "b" + it.url }) { b ->
                    ListItem(
                        headlineContent = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(b.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) },
                        leadingContent = { Icon(Icons.Filled.Bookmark, null) },
                        trailingContent = { IconButton(onClick = { c.removeBookmark(b) }) { Icon(Icons.Filled.Delete, "حذف") } },
                        modifier = Modifier.clickable { c.load(b.url); onDismiss() },
                    )
                }
            } else {
                items(c.history.toList(), key = { "h" + it.time + it.url }) { h ->
                    ListItem(
                        headlineContent = { Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(h.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) },
                        modifier = Modifier.clickable { c.load(h.url); onDismiss() },
                    )
                }
            }
        }
    }
}


