@file:OptIn(ExperimentalMaterial3Api::class)

package com.ali.files.download

import android.content.ClipboardManager
import android.net.Uri
import android.webkit.URLUtil
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.files.util.AppSettings
import com.ali.files.util.FileStore
import com.ali.files.util.formatBytes
import com.ali.files.util.formatDuration
import com.ali.files.util.kindIcon
import com.ali.files.util.kindOf
import kotlinx.coroutines.launch

private enum class DFilter(val label: String) { ALL("الكل"), ACTIVE("جاري"), DONE("مكتمل"), FAILED("فشل") }

@Composable
fun DownloadsScreen(onRequest: (DownloadRequest) -> Unit, onOpenVault: () -> Unit) {
    val items by Downloader.items.collectAsState()
    var filter by remember { mutableStateOf(DFilter.ALL) }
    var addLink by remember { mutableStateOf(false) }
    val shown = items.filter {
        when (filter) {
            DFilter.ALL -> true
            DFilter.ACTIVE -> it.active || it.status == DStatus.PAUSED
            DFilter.DONE -> it.status == DStatus.COMPLETED
            DFilter.FAILED -> it.status == DStatus.FAILED
        }
    }
    val totalSpeed = items.filter { it.status == DStatus.RUNNING }.sumOf { it.speed }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("التحميلات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (totalSpeed > 0) "السرعة الكلية ${formatBytes(totalSpeed)}/ث" else "${items.size} تحميل",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (items.any { it.active }) IconButton(onClick = { Downloader.pauseAll() }) { Icon(Icons.Filled.Pause, "إيقاف الكل") }
                if (items.any { it.status == DStatus.PAUSED }) IconButton(onClick = { Downloader.resumeAll() }) { Icon(Icons.Filled.PlayArrow, "استكمال الكل") }
                if (items.any { it.status == DStatus.COMPLETED }) TextButton(onClick = { Downloader.clearFinished() }) { Text("امسح المكتمل") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
            }
            if (shown.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Filled.Download, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("مفيش تحميلات", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "حمّل من المتصفح، أو شارك أي لينك للتطبيق، أو اضغط «لينك جديد».",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { DownloadRow(it, onOpenVault) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { addLink = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("لينك جديد") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (addLink) AddLinkDialog(onDismiss = { addLink = false }) { url ->
        addLink = false
        onRequest(DownloadRequest(url, URLUtil.guessFileName(url, null, null)))
    }
}

@Composable
private fun DownloadRow(d: DownloadItem, onOpenVault: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val kind = kindOf(d.mime, d.fileName)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(kind), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                if (d.status != DStatus.COMPLETED) {
                    Spacer(Modifier.height(6.dp))
                    val f = d.fraction
                    if (f >= 0) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    else if (d.status == DStatus.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
                Text(statusLine(d), fontSize = 12.sp, color = if (d.status == DStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when (d.status) {
                DStatus.RUNNING, DStatus.QUEUED -> IconButton(onClick = { Downloader.pause(d.id) }) { Icon(Icons.Filled.Pause, "إيقاف مؤقت") }
                DStatus.PAUSED -> IconButton(onClick = { Downloader.resume(d.id) }) { Icon(Icons.Filled.PlayArrow, "استكمال") }
                DStatus.FAILED -> IconButton(onClick = { Downloader.resume(d.id) }) { Icon(Icons.Filled.Refresh, "إعادة المحاولة") }
                DStatus.COMPLETED -> if (d.savedInVault) {
                    IconButton(onClick = onOpenVault) { Icon(Icons.Filled.Lock, "في الخزنة", tint = MaterialTheme.colorScheme.secondary) }
                } else if (d.savedUri != null) {
                    IconButton(onClick = { FileStore.open(ctx, Uri.parse(d.savedUri), d.mime) }) { Icon(Icons.Filled.Folder, "فتح") }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "خيارات") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (d.status == DStatus.COMPLETED && d.savedUri != null) {
                        DropdownMenuItem(text = { Text("مشاركة") }, onClick = { menu = false; FileStore.share(ctx, Uri.parse(d.savedUri), d.mime) })
                        DropdownMenuItem(text = { Text("انقله للخزنة 🔒") }, onClick = {
                            menu = false
                            scope.launch {
                                runCatching { Downloader.moveToVault(d.id) }
                                    .onSuccess { Toast.makeText(ctx, "اتنقل للخزنة", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(ctx, it.message ?: "تعذر النقل", Toast.LENGTH_SHORT).show() }
                            }
                        })
                    }
                    DropdownMenuItem(text = { Text("انسخ الرابط") }, onClick = {
                        menu = false
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("url", d.url))
                    })
                    DropdownMenuItem(text = { Text("شيله من القائمة") }, onClick = { menu = false; Downloader.remove(d.id, deleteFile = false) })
                    if (d.status == DStatus.COMPLETED && d.savedUri != null) {
                        DropdownMenuItem(
                            text = { Text("امسح الملف كمان", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; Downloader.remove(d.id, deleteFile = true) },
                        )
                    }
                }
            }
        }
    }
}

private fun statusLine(d: DownloadItem): String {
    val size = if (d.total > 0) "${formatBytes(d.downloaded)} / ${formatBytes(d.total)}" else formatBytes(d.downloaded)
    return when (d.status) {
        DStatus.QUEUED -> if (d.ytdlp) "بيجهّز الفيديو…" else "في الانتظار…"
        DStatus.RUNNING -> buildString {
            if (d.ytdlp && d.note != null) append(d.note).append(" • ")
            if (d.hls) append("جزء ${d.hlsIndex} من ${d.hlsTotal} • ${formatBytes(d.downloaded)}") else append(size)
            if (d.speed > 0) {
                append(" • ${formatBytes(d.speed)}/ث")
                if (d.total > 0 && !d.hls) append(" • باقي ${formatDuration((d.total - d.downloaded) / d.speed.coerceAtLeast(1))}")
                else if (d.hls && d.hlsIndex > 0) {
                    val est = d.downloaded.toDouble() / d.hlsIndex * (d.hlsTotal - d.hlsIndex)
                    append(" • باقي ${formatDuration((est / d.speed.coerceAtLeast(1)).toLong())}")
                }
            }
            if (!d.hls && d.segments.size > 1) append(" • ${d.segments.size} خيوط")
            if (d.toVault) append(" • 🔒")
        }
        DStatus.PAUSED -> "متوقف • " + (if (d.hls) "جزء ${d.hlsIndex} من ${d.hlsTotal}" else size)
        DStatus.FAILED -> "فشل: ${d.error ?: "خطأ غير معروف"}"
        DStatus.COMPLETED -> formatBytes(d.downloaded) + if (d.savedInVault) " • في الخزنة 🔒" else " • Download/AliFiles"
    }
}

@Composable
private fun AddLinkDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحميل لينك") },
        text = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, singleLine = false, maxLines = 4,
                    placeholder = { Text("https://…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        IconButton(onClick = {
                            ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.let { url = it.toString().trim() }
                        }) { Icon(Icons.Filled.ContentPaste, "لصق") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = url.trim().startsWith("http"), onClick = { onOk(url.trim()) }) { Text("التالي") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/** Confirms a download: file name, number of threads, destination (Downloads or encrypted vault). */
@Composable
fun DownloadDialog(req: DownloadRequest, onDismiss: () -> Unit, onStarted: (toVault: Boolean) -> Unit) {
    var name by remember(req) { mutableStateOf(req.fileName) }
    var threads by remember(req) { mutableFloatStateOf(AppSettings.threads.intValue.toFloat()) }
    var toVault by remember(req) { mutableStateOf(AppSettings.saveToVault.value) }
    val hls = Downloader.isHlsUrl(req.url, req.mime)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحميل ملف") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم الملف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(req.url, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    buildString {
                        if (req.size > 0) append("الحجم: ${formatBytes(req.size)}   ")
                        if (hls) append("بث HLS — هيتجمع في ملف واحد .ts")
                    },
                    fontSize = 12.sp,
                )
                Text(if (hls) "عدد الأجزاء مع بعض: ${threads.toInt()}" else "عدد الخيوط (أسرع مع السيرفرات اللي بتدعم): ${threads.toInt()}", fontSize = 13.sp)
                Slider(value = threads, onValueChange = { threads = it }, valueRange = 1f..16f, steps = 14)
                Text("الحفظ في:", fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !toVault, onClick = { toVault = false }); Text("التنزيلات")
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = toVault, onClick = { toVault = true }); Text("الخزنة 🔒")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                Downloader.enqueue(req.copy(fileName = name.trim()), threads.toInt(), toVault)
                onStarted(toVault)
            }) { Text("ابدأ التحميل") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
