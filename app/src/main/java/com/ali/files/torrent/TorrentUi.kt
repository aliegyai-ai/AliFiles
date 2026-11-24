package com.ali.files.torrent

import com.ali.files.ui.sheetBody
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.files.player.PlayerHub
import com.ali.files.player.Track
import com.ali.files.util.AppSettings
import com.ali.files.util.formatBytes
import com.ali.files.util.mimeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where a torrent comes from. */
sealed class TorrentSource {
    data class Magnet(val uri: String) : TorrentSource()
    class Bytes(val data: ByteArray) : TorrentSource()
    data class Link(val url: String, val headers: Map<String, String>) : TorrentSource()
    data class Content(val uri: Uri) : TorrentSource()
}

/** A torrent waiting for the «pick files» sheet (from the browser, another app, or the downloads screen). */
object TorrentInbox {
    val pending = androidx.compose.runtime.mutableStateOf<Pair<TorrentSource, Boolean>?>(null)
    /** show the «تورنت» part of the downloads screen */
    val showTorrents = androidx.compose.runtime.mutableStateOf(false)
    fun open(s: TorrentSource, private: Boolean = false) { pending.value = s to private }
}

fun isTorrentLink(url: String, mime: String? = null): Boolean =
    url.startsWith("magnet:", true) || mime == "application/x-bittorrent" ||
        url.substringBefore('?').substringBefore('#').endsWith(".torrent", true)

private suspend fun load(c: Context, s: TorrentSource): PendingTorrent = when (s) {
    is TorrentSource.Magnet -> TorrentEngine.fromMagnet(s.uri)
    is TorrentSource.Bytes -> TorrentEngine.fromBytes(s.data)
    is TorrentSource.Content -> withContext(Dispatchers.IO) {
        TorrentEngine.fromBytes(c.contentResolver.openInputStream(s.uri)!!.use { it.readBytes() })
    }
    is TorrentSource.Link -> withContext(Dispatchers.IO) {
        if (s.url.startsWith("magnet:", true)) return@withContext TorrentEngine.fromMagnet(s.url)
        val rb = okhttp3.Request.Builder().url(s.url)
        s.headers.forEach { (k, v) -> rb.header(k, v) }
        val bytes = com.ali.files.download.Downloader.client.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) error("مقدرتش أنزّل ملف التورنت (${r.code})")
            r.body!!.bytes()
        }
        TorrentEngine.fromBytes(bytes)
    }
}

private fun isVideoName(n: String) = mimeOf(n).startsWith("video/")
private fun isAudioName(n: String) = mimeOf(n).startsWith("audio/")

/** Before starting: pick the files, in-order download, library or vault. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentAddSheet(source: TorrentSource, private: Boolean, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<PendingTorrent?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var sequential by remember { mutableStateOf(false) }
    var toVault by remember { mutableStateOf(private || AppSettings.saveToVault.value) }
    LaunchedEffect(source) {
        runCatching { load(ctx, source) }
            .onSuccess { p ->
                pending = p
                selected = p.files.indices.toSet()
                // one video (a film) → in order, so it can be watched while downloading
                sequential = p.files.count { isVideoName(it.first) } == 1
            }
            .onFailure { error = it.message ?: "التورنت ده مش صالح" }
    }
    val sheet = com.ali.files.ui.rememberGuardedSheet()
    val close = { sheet.close(onDismiss) }
    ModalBottomSheet(onDismissRequest = {}, sheetState = sheet.state) {
        Column(Modifier.fillMaxWidth().sheetBody().padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = close) { Icon(androidx.compose.material.icons.Icons.Filled.Close, "قفل") }
            }
            val p = pending
            when {
                error != null -> {
                    Text("تورنت", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("قفل") }
                }
                p == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 30.dp)) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("بيجيب معلومات التورنت…", fontWeight = FontWeight.Bold)
                            Text("لينكات الـ magnet ممكن تاخد لحد دقيقة", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else -> {
                    Text(p.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val total = selected.sumOf { p.files[it].second }
                    Text("${selected.size} من ${p.files.size} ملف · ${formatBytes(total)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (p.files.size > 1) Row {
                        TextButton(onClick = { selected = p.files.indices.toSet() }) { Text("الكل") }
                        TextButton(onClick = { selected = p.files.indices.filter { isVideoName(p.files[it].first) }.toSet() }) { Text("الفيديوهات بس") }
                        TextButton(onClick = { selected = emptySet() }) { Text("ولا حاجة") }
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(p.files) { i, (path, size) ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .clickable { selected = if (i in selected) selected - i else selected + i }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = i in selected, onCheckedChange = { selected = if (it) selected + i else selected - i })
                                Column(Modifier.weight(1f)) {
                                    Text(path.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                    Text(formatBytes(size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchLine("حمّل بالترتيب", "عشان تقدر تتفرج على الفيديو وهو بيحمّل", sequential) { sequential = it }
                    SwitchLine("احفظ في الخزنة", "مشفّر، بدل المكتبة", toVault) { toVault = it }
                    if (AppSettings.torrentWifiOnly.value) Text("📶 هيحمّل على الواي فاي بس (من الإعدادات)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = selected.isNotEmpty(),
                        onClick = { TorrentEngine.start(p, selected, sequential, toVault); sheet.close(onStarted) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) { Text("ابدأ التحميل", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun SwitchLine(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The «تورنت» part of the downloads screen. */
@Composable
fun TorrentList(onAdd: (TorrentSource) -> Unit) {
    val items by TorrentEngine.items.collectAsState()
    var addDialog by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<TorrentItem?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { onAdd(TorrentSource.Content(it)) } }

    Box(Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("🧲", fontSize = 54.sp)
                Spacer(Modifier.height(10.dp))
                Text("مفيش تورنت", style = MaterialTheme.typography.titleMedium)
                Text(
                    "افتح لينك magnet من المتصفح أو من أي تطبيق، أو اضغط «تورنت جديد» والصق اللينك أو اختار ملف .torrent",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (items.any { it.rec.done }) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { TorrentEngine.clearFinished() }) { Text("امسح المكتمل من القايمة") }
                    }
                }
                items(items, key = { it.rec.hash }) { TorrentRow(it) { removing = it } }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { addDialog = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("تورنت جديد") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (addDialog) AddTorrentDialog(
        onDismiss = { addDialog = false },
        onPickFile = { addDialog = false; pick.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
        onMagnet = { addDialog = false; onAdd(TorrentSource.Magnet(it)) },
    )
    removing?.let { t ->
        AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), 
            onDismissRequest = { removing = null },
            title = { Text("تشيل التورنت ده؟") },
            text = { Text(if (t.rec.done) "هيتشال من القايمة بس، الملفات هتفضل ${t.rec.result ?: ""}." else "اللي اتحمّل منه لحد دلوقتي هيتمسح.") },
            confirmButton = { TextButton(onClick = { TorrentEngine.remove(t.rec.hash, deleteFiles = !t.rec.done); removing = null }) { Text("شيله", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun TorrentRow(t: TorrentItem, onRemove: () -> Unit) {
    val gold = MaterialTheme.colorScheme.primary
    val r = t.rec
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(gold.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Text("🧲", fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(t.state, fontSize = 12.sp, color = if (r.done) gold else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!r.done) IconButton(onClick = { if (r.paused) TorrentEngine.resume(r.hash) else TorrentEngine.pause(r.hash) }) {
                    Icon(if (r.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (r.paused) "كمّل" else "وقّف", tint = gold)
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "شيل", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (!r.done) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = gold)
                Spacer(Modifier.height(6.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Text(
                        "${(t.progress * 100).toInt()}% · ${formatBytes(t.doneBytes)} من ${formatBytes(r.wanted)}" +
                            (if (t.down > 0) " · ⬇ ${formatBytes(t.down.toLong())}/ث" else "") +
                            (if (t.up > 0) " · ⬆ ${formatBytes(t.up.toLong())}/ث" else "") +
                            (if (t.peers > 0) " · ${t.peers} متصل" else "") +
                            eta(t),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // watch while downloading
                val playable = t.files.filter { (_, path, p) -> (isVideoName(path) || isAudioName(path)) && p >= 0.03f }
                if (playable.isNotEmpty() && !t.seeding) {
                    Spacer(Modifier.height(4.dp))
                    playable.take(3).forEach { (_, path, p) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { play(path) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.PlayCircle, null, tint = gold, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("شغّل وهو بيحمّل: " + File(path).name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${(p * 100).toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!r.sequential) TextButton(onClick = { TorrentEngine.setSequential(r.hash, true) }) { Text("حمّل بالترتيب عشان التشغيل يبقى أسلس", fontSize = 12.sp) }
                }
            }
        }
    }
}

private fun eta(t: TorrentItem): String {
    if (t.down <= 0 || t.rec.wanted <= t.doneBytes) return ""
    val s = (t.rec.wanted - t.doneBytes) / t.down
    return " · فاضل " + when {
        s >= 3600 -> "${s / 3600} س ${(s % 3600) / 60} د"
        s >= 60 -> "${s / 60} د"
        else -> "$s ث"
    }
}

private fun play(path: String) {
    val f = File(path)
    PlayerHub.play(listOf(Track(id = path, uri = Uri.fromFile(f), title = f.nameWithoutExtension, subtitle = "تورنت", isVideo = isVideoName(f.name))))
}

@Composable
private fun AddTorrentDialog(onDismiss: () -> Unit, onPickFile: () -> Unit, onMagnet: (String) -> Unit) {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf("") }
    AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), 
        onDismissRequest = onDismiss,
        title = { Text("تورنت جديد") },
        text = {
            Column {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = link, onValueChange = { link = it }, maxLines = 4,
                        placeholder = { Text("magnet:?xt=…") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        trailingIcon = {
                            IconButton(onClick = {
                                ctx.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.let { link = it.toString().trim() }
                            }) { Icon(Icons.Filled.ContentPaste, "لصق") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) { Text("📄  اختار ملف .torrent") }
            }
        },
        confirmButton = { TextButton(enabled = link.trim().startsWith("magnet:", true), onClick = { onMagnet(link.trim()) }) { Text("التالي") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
