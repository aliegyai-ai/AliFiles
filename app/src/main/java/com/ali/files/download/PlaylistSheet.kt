package com.ali.files.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.files.library.Library
import com.ali.files.util.AppSettings
import com.ali.files.util.formatBytes
import com.ali.files.util.sanitizeFileName

/** rough size per minute of video at each height (for the total shown before starting) */
private val mbPerMin = mapOf(360 to 3L, 480 to 5L, 720 to 10L, 1080 to 20L)

/**
 * A whole playlist / channel: see every video, pick which ones, one quality for all, the total size.
 * They download 3 at a time into a library folder named after the list, numbered in order, and the
 * list becomes a playlist in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheet(url: String, headers: Map<String, String>, onDismiss: () -> Unit, onStarted: () -> Unit) {
    var info by remember { mutableStateOf<VideoExtractor.PlaylistInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    var height by remember { mutableStateOf<Int?>(null) } // null = audio only
    var audioQ by remember { mutableStateOf(runCatching { AudioQ.valueOf(AppSettings.audioQuality.value) }.getOrDefault(AudioQ.HIGH)) }
    var fmt by remember { mutableStateOf(AppSettings.audioFormat.value) }
    val files by Library.files.collectAsState()

    LaunchedEffect(url) {
        runCatching { VideoExtractor.playlist(url, headers) }
            .onSuccess { p ->
                info = p
                // already downloaded before (same folder + number): leave them unticked
                val have = files.filter { it.folder == sanitizeFileName(p.title) }.map { it.title.substringBefore(" - ") }.toSet()
                picked = p.entries.indices.filter { "%02d".format(it + 1) !in have }.toSet()
            }
            .onFailure { error = it.message?.lineSequence()?.lastOrNull { l -> l.isNotBlank() }?.take(200) ?: "تعذر قراءة القايمة" }
    }

    val close = onDismiss
    com.ali.files.ui.OverlaySheet(onBack = close) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info?.title ?: "قايمة تشغيل", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = close) { Icon(Icons.Filled.Close, "قفل") }
            }
            val p = info
            when {
                error != null -> {
                    Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 16.dp))
                    TextButton(onClick = close) { Text("قفل") }
                }
                p == null -> Row(Modifier.padding(vertical = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("بيقرا القايمة…")
                }
                else -> {
                    val chosen = picked.sorted().map { p.entries[it] }
                    val secs = chosen.sumOf { it.durationSec }
                    val total = if (height == null) audioQ.bytes(fmt, secs) else (mbPerMin[height] ?: 10L) * 1024 * 1024 * secs / 60
                    Text("${p.entries.size} فيديو • متحدد ${chosen.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton(onClick = { picked = p.entries.indices.toSet() }) { Text("الكل") }
                        TextButton(onClick = { picked = emptySet() }) { Text("ولا حاجة") }
                    }
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        itemsIndexed(p.entries) { i, e ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { picked = if (i in picked) picked - i else picked + i }.padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = i in picked, onCheckedChange = { picked = if (it) picked + i else picked - i })
                                Box(Modifier.width(72.dp).height(42.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                    Thumb(e.thumbnail, Modifier.fillMaxWidth().height(42.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${i + 1}. ${e.title}", fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (e.durationSec > 0) Text(com.ali.files.player.formatTime(e.durationSec * 1000), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("الجودة لكل الفيديوهات", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = height == null, onClick = { height = null }, label = { Text("صوت بس") })
                        listOf(360, 720, 1080).forEach { h -> FilterChip(selected = height == h, onClick = { height = h }, label = { Text("${h}p") }) }
                    }
                    if (height == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AudioQ.entries.forEach { q -> FilterChip(selected = audioQ == q, onClick = { audioQ = q }, label = { Text(q.label) }) }
                    }
                    if (height == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = fmt == "mp3", onClick = { fmt = "mp3" }, label = { Text("MP3") })
                        FilterChip(selected = fmt == "m4a", onClick = { fmt = "m4a" }, label = { Text("M4A (أصغر)") })
                    }
                    Text(
                        "هيتحفظوا في مجلد «${p.title}» بالترتيب، ويتعملوا قايمة تشغيل جاهزة.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = chosen.isNotEmpty(),
                        onClick = {
                            if (height == null) { AppSettings.setAudioQuality(audioQ.name); AppSettings.setAudioFormat(fmt) }
                            val folder = sanitizeFileName(p.title).take(60)
                            picked.sorted().forEach { i ->
                                val e = p.entries[i]
                                Downloader.enqueueVideo(
                                    e.url, "%02d".format(i + 1) + " - " + e.title, height, headers,
                                    if (height == null) audioQ else null, fmt,
                                    libFolder = folder, group = p.title.take(60),
                                )
                            }
                            onStarted()
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Filled.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("حمّل ${chosen.size}" + (if (total > 0) " (~${formatBytes(total)})" else ""), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
