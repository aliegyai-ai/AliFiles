@file:OptIn(ExperimentalMaterial3Api::class)

package com.ali.files.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ali.files.ui.UiState

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", text))
    Toast.makeText(ctx, "اتنسخ", Toast.LENGTH_SHORT).show()
}

@Composable
fun BrowserScreen(c: BrowserController, ui: UiState) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val tab = c.current
    var showTabs by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }

    BackHandler(enabled = tab?.canBack?.value == true) { tab?.webView?.goBack() }
    BackHandler(enabled = findOpen) { findOpen = false; tab?.webView?.clearMatches() }
    BackHandler(enabled = c.fullscreen.value) { c.exitFullscreen() }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------- address bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AddressField(c, tab, Modifier.weight(1f)) { focus.clearFocus() }
            IconButton(onClick = {
                val p = tab?.progress?.intValue ?: 100
                if (p < 100) tab?.webView?.stopLoading() else tab?.webView?.reload()
            }) {
                Icon(if ((tab?.progress?.intValue ?: 100) < 100) Icons.Filled.Close else Icons.Filled.Refresh, "تحديث")
            }
        }
        val progress = tab?.progress?.intValue ?: 100
        if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        else Spacer(Modifier.height(2.dp))

        if (findOpen) FindBar(
            query = findQuery,
            onQuery = { findQuery = it; tab?.webView?.findAllAsync(it) },
            onNext = { tab?.webView?.findNext(true) },
            onPrev = { tab?.webView?.findNext(false) },
            onClose = { findOpen = false; tab?.webView?.clearMatches() },
        )

        // ---------------------------------------------------- page
        Box(Modifier.weight(1f).fillMaxWidth()) {
        AndroidView(
            factory = { context -> FrameLayout(context) },
            modifier = Modifier.fillMaxSize(),
            update = { container ->
                val wv = tab?.webView
                if (wv != null && (container.childCount != 1 || container.getChildAt(0) !== wv)) {
                    container.removeAllViews()
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    container.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            },
        )
        // floating download button: shows when a video in the page is playing
        val videoSite = tab?.url?.value?.let { com.ali.files.download.VideoExtractor.isVideoPage(it) } == true
        if (tab != null && (tab.playing.value || videoSite)) {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { c.downloadVideo(tab) },
                icon = { Icon(Icons.Filled.Download, null) },
                text = { Text("حمّل الفيديو") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            )
        }
        }

        // ---------------------------------------------------- toolbar
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = tab?.canBack?.value == true, onClick = { tab?.webView?.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
            IconButton(enabled = tab?.canForward?.value == true, onClick = { tab?.webView?.goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "قدام") }
            IconButton(onClick = { c.load("") }) { Icon(Icons.Filled.Home, "الرئيسية") }
            val mediaCount = tab?.media?.size ?: 0
            val videoSite = tab?.url?.value?.let { com.ali.files.download.VideoExtractor.isVideoPage(it) } == true
            IconButton(onClick = {
                if (videoSite && tab != null) c.requestVideo(tab) else { tab?.let { c.sniffDom(it) }; showMedia = true }
            }) {
                BadgedBox(badge = { if (mediaCount > 0) Badge { Text("$mediaCount") } }) {
                    Icon(Icons.Filled.VideoLibrary, "الوسائط", tint = if (mediaCount > 0 || videoSite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { showTabs = true }) {
                Surface(shape = RoundedCornerShape(6.dp), border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface), color = MaterialTheme.colorScheme.surface) {
                    Text("${c.tabs.size}", Modifier.padding(horizontal = 7.dp, vertical = 1.dp), fontSize = 13.sp)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "القائمة") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("تبويب جديد") }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { menu = false; c.newTab() })
                    DropdownMenuItem(text = { Text("تبويب خاص (بدون سجل)") }, leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) }, onClick = { menu = false; c.newTab(incognito = true) })
                    if (tab != null) {
                        val marked = c.isBookmarked(tab.url.value)
                        DropdownMenuItem(
                            text = { Text(if (marked) "شيل من المفضلة" else "ضيف للمفضلة") },
                            leadingIcon = { Icon(if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null) },
                            onClick = { menu = false; c.toggleBookmark(tab) },
                        )
                    }
                    DropdownMenuItem(text = { Text("المفضلة والسجل") }, leadingIcon = { Icon(Icons.Filled.History, null) }, onClick = { menu = false; showLibrary = true })
                    DropdownMenuItem(text = { Text("بحث في الصفحة") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, onClick = { menu = false; findOpen = true })
                    if (tab != null) {
                        DropdownMenuItem(
                            text = { Text("وضع الكمبيوتر") },
                            leadingIcon = { Icon(Icons.Filled.Public, null) },
                            trailingIcon = { Checkbox(checked = tab.desktop.value, onCheckedChange = null) },
                            onClick = { menu = false; c.setDesktop(tab, !tab.desktop.value) },
                        )
                        DropdownMenuItem(text = { Text("حمّل الفيديو من الصفحة دي") }, leadingIcon = { Icon(Icons.Filled.VideoLibrary, null) }, onClick = { menu = false; c.requestVideo(tab) })
                        DropdownMenuItem(text = { Text("حمّل الرابط ده") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { menu = false; c.requestDownload(tab.url.value, tab) })
                        DropdownMenuItem(text = { Text("مشاركة الرابط") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = {
                            menu = false
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url.value), "مشاركة"))
                        })
                        DropdownMenuItem(text = { Text("نسخ الرابط") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menu = false; copy(ctx, tab.url.value) })
                    }
                }
            }
        }
    }

    // ---------------------------------------------------- sheets & dialogs
    if (showTabs) TabsSheet(c) { showTabs = false }
    if (showMedia && tab != null) MediaSheet(c, tab) { showMedia = false }
    if (showLibrary) LibrarySheet(c) { showLibrary = false }

    ui.longPress.value?.let { lp ->
        AlertDialog(
            onDismissRequest = { ui.longPress.value = null },
            title = { Text(if (lp.isImage) "صورة" else "رابط", maxLines = 1) },
            text = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(lp.url, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { ui.longPress.value = null; c.newTab(lp.url, lp.incognito) }) { Text("افتح في تبويب جديد") }
                    TextButton(onClick = { ui.longPress.value = null; c.requestDownload(lp.url, c.current) }) { Text(if (lp.isImage) "حمّل الصورة" else "حمّل الرابط") }
                    TextButton(onClick = { ui.longPress.value = null; copy(ctx, lp.url) }) { Text("انسخ الرابط") }
                }
            },
        )
    }
}

@Composable
private fun AddressField(c: BrowserController, tab: BrowserTab?, modifier: Modifier, onDone: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val url = tab?.url?.value ?: ""
    LaunchedEffect(url, editing, tab?.id) { if (!editing) text = TextFieldValue(url) }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.height(44.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                tab?.incognito == true -> Icon(Icons.Filled.VisibilityOff, "خاص", Modifier.size(18.dp))
                (tab?.blocked?.intValue ?: 0) > 0 -> BadgedBox(badge = { Badge { Text("${tab?.blocked?.intValue}", fontSize = 9.sp) } }) {
                    Icon(Icons.Filled.Shield, "مانع الإعلانات", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                tab?.secure == true -> Icon(Icons.Filled.Lock, "آمن", Modifier.size(18.dp))
                else -> Icon(Icons.Filled.Public, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
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
                        editing = f.isFocused
                        if (f.isFocused) text = text.copy(selection = TextRange(0, text.text.length))
                    },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.text.isEmpty()) Text("ابحث أو اكتب رابط", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            inner()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("دور في الصفحة…", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } },
        )
        IconButton(onClick = onPrev) { Icon(Icons.Filled.KeyboardArrowUp, "السابق") }
        IconButton(onClick = onNext) { Icon(Icons.Filled.KeyboardArrowDown, "التالي") }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "إغلاق") }
    }
}

@Composable
private fun TabsSheet(c: BrowserController, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { c.newTab(); onDismiss() }) { Icon(Icons.Filled.Add, null); Text("جديد") }
            OutlinedButton(onClick = { c.newTab(incognito = true); onDismiss() }) { Icon(Icons.Filled.VisibilityOff, null); Spacer(Modifier.width(4.dp)); Text("خاص") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { c.closeAll(); onDismiss() }) { Text("اقفل الكل") }
        }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            items(c.tabs.toList(), key = { it.id }) { t ->
                val selected = t.id == c.currentId.longValue
                ListItem(
                    headlineContent = { Text(t.title.value.ifBlank { "صفحة جديدة" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(t.url.value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) },
                    leadingContent = { Icon(if (t.incognito) Icons.Filled.VisibilityOff else Icons.Filled.Public, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { IconButton(onClick = { c.closeTab(t.id) }) { Icon(Icons.Filled.Close, "اقفل") } },
                    modifier = Modifier.clickable { c.select(t.id); onDismiss() }
                        .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier),
                )
            }
        }
    }
}

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
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
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
        LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
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
