@file:OptIn(ExperimentalMaterial3Api::class)

package com.ali.files.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.URLUtil
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.files.util.AppSettings
import com.ali.files.util.formatBytes
import com.ali.files.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private sealed interface Load {
    data object Loading : Load
    data class Ready(val meta: VideoMeta, val suggestion: Suggestion) : Load
    data class Failed(val message: String) : Load
}

private const val AUDIO = -1

/**
 * Bottom sheet: reads the video's details, suggests audio-only or a video quality (with the reason),
 * and lets the user change it before downloading. The whole sheet scrolls, so long quality lists fit.
 */
@Composable
fun VideoDialog(
    req: VideoRequest,
    onDismiss: () -> Unit,
    onStarted: (toVault: Boolean) -> Unit,
    onFallback: (DownloadRequest) -> Unit,
) {
    var state by remember(req) { mutableStateOf<Load>(Load.Loading) }
    var choice by remember(req) { mutableStateOf<Int?>(null) } // AUDIO or a height
    var toVault by remember(req) { mutableStateOf(AppSettings.saveToVault.value) }

    LaunchedEffect(req) {
        state = try {
            val m = VideoExtractor.info(req)
            val s = VideoExtractor.suggest(m)
            choice = if (s.audioOnly) AUDIO else (s.height ?: m.heights.firstOrNull()?.height ?: AUDIO)
            Load.Ready(m, s)
        } catch (e: Throwable) {
            Load.Failed(e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(220) ?: "تعذر قراءة الفيديو")
        }
    }

    val scheme = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val s = state) {
                Load.Loading -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // what the page already told us, shown right away
                    if (req.title != null || req.thumbnail != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumb(req.thumbnail, Modifier.width(120.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(req.title ?: "", fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("بيجيب الجودات المتاحة…")
                    }
                }

                is Load.Failed -> {
                    Text("معرفتش أقرا الفيديو", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(s.message, fontSize = 12.sp, color = scheme.error)
                    Text("لو الفيديو خاص أو محتاج تسجيل دخول، افتحه في متصفح التطبيق وسجّل دخول، وبعدين دوس «حمّل الفيديو».", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("إلغاء") }
                        Button(onClick = {
                            onFallback(DownloadRequest(req.url, URLUtil.guessFileName(req.url, null, null), headers = req.headers))
                        }, modifier = Modifier.weight(1f)) { Text("حمّله كملف عادي") }
                    }
                }

                is Load.Ready -> {
                    // header: thumbnail + title
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumb(s.meta.thumbnail, Modifier.width(120.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.meta.title, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                            Text(
                                listOfNotNull(
                                    s.meta.uploader.takeIf { it.isNotBlank() },
                                    s.meta.durationSec.takeIf { it > 0 }?.let { formatDuration(it) },
                                ).joinToString(" • "),
                                fontSize = 12.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // the suggestion
                    Surface(color = scheme.primary.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lightbulb, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "مقترح: " + if (s.suggestion.audioOnly) "صوت بس (MP3)" else "فيديو ${s.suggestion.height ?: ""}p",
                                    fontWeight = FontWeight.Bold, color = scheme.primary, fontSize = 13.sp,
                                )
                                Text(s.suggestion.reason, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                            }
                        }
                    }

                    // audio only
                    Text("صوت بس", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    QualityCard(
                        selected = choice == AUDIO, audio = true, label = "MP3",
                        size = s.meta.audio.approxBytes, recommended = s.suggestion.audioOnly,
                        modifier = Modifier.fillMaxWidth(),
                    ) { choice = AUDIO }

                    // video qualities, three per row
                    if (s.meta.heights.isNotEmpty()) {
                        Text("فيديو", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                        s.meta.heights.filter { it.height != null }.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { o ->
                                    val h = o.height!!
                                    QualityCard(
                                        selected = choice == h, audio = false, label = qualityName(h),
                                        size = o.approxBytes, recommended = !s.suggestion.audioOnly && s.suggestion.height == h,
                                        modifier = Modifier.weight(1f),
                                    ) { choice = h }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }

                    // where to save
                    Text("الحفظ في", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SaveChoice("المكتبة", Icons.Filled.Folder, !toVault, Modifier.weight(1f)) { toVault = false }
                        SaveChoice("الخزنة", Icons.Filled.Lock, toVault, Modifier.weight(1f)) { toVault = true }
                    }

                    Spacer(Modifier.height(4.dp))
                    Button(
                        enabled = choice != null,
                        onClick = {
                            val c = choice ?: return@Button
                            Downloader.enqueueVideo(req.url, s.meta.title, if (c == AUDIO) null else c, req.headers, toVault)
                            onStarted(toVault)
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Filled.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text(startLabel(choice, s.meta), fontWeight = FontWeight.Bold)
                    }
                    // play right away, without downloading
                    OutlinedButton(
                        enabled = choice != null && s.meta.streams.isNotEmpty(),
                        onClick = {
                            val c = choice ?: return@OutlinedButton
                            val pick = VideoExtractor.streamFor(s.meta, if (c == AUDIO) null else c)
                            if (pick == null) {
                                android.widget.Toast.makeText(ctx, "مقدرتش ألاقي رابط تشغيل مباشر للجودة دي", android.widget.Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            com.ali.files.player.PlayerHub.playStream(
                                com.ali.files.player.Track(
                                    id = req.url + "#" + c, uri = android.net.Uri.parse(req.url), title = s.meta.title,
                                    subtitle = s.meta.uploader.ifBlank { s.meta.site }, isVideo = c != AUDIO, artwork = s.meta.thumbnail,
                                ),
                                pick,
                            )
                            onDismiss()
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("شغّل من غير تحميل", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("إلغاء") }
                }
            }
        }
    }
}

private fun qualityName(h: Int) = when {
    h >= 2160 -> "4K"
    h >= 1440 -> "2K"
    else -> "${h}p"
}

private fun startLabel(choice: Int?, meta: VideoMeta): String {
    val size = when (choice) {
        null -> 0L
        AUDIO -> meta.audio.approxBytes
        else -> meta.heights.firstOrNull { it.height == choice }?.approxBytes ?: 0L
    }
    val what = when (choice) { null -> ""; AUDIO -> " صوت MP3"; else -> " ${qualityName(choice)}" }
    return "ابدأ التحميل$what" + if (size > 0) " (~${formatBytes(size)})" else ""
}

@Composable
private fun QualityCard(
    selected: Boolean, audio: Boolean, label: String, size: Long, recommended: Boolean,
    modifier: Modifier, onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainerHigh,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        modifier = modifier.heightIn(min = 64.dp),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (audio) Icons.Filled.AudioFile else Icons.Filled.Movie, null, Modifier.size(16.dp), tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (selected) scheme.primary else scheme.onSurface)
            }
            Text(
                when {
                    recommended && size > 0 -> "⭐ ~${formatBytes(size)}"
                    recommended -> "⭐ المقترح"
                    size > 0 -> "~${formatBytes(size)}"
                    else -> " "
                },
                fontSize = 11.sp, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1,
            )
        }
    }
}

@Composable
private fun SaveChoice(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainerHigh,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        modifier = modifier.height(46.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun Thumb(url: String?, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, url) {
        if (url != null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    Downloader.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        val b = r.body?.bytes() ?: return@use null
                        BitmapFactory.decodeByteArray(b, 0, b.size)
                    }
                }.getOrNull()
            }
        }
    }
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
