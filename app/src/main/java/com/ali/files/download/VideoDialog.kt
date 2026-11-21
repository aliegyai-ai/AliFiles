package com.ali.files.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.URLUtil
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Reads the video's details, suggests audio-only or a video quality (with the reason),
 * and lets the user change it before downloading.
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحميل فيديو") },
        text = {
            when (val s = state) {
                Load.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("بيقرا الفيديو والجودات المتاحة…")
                }
                is Load.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("معرفتش أقرا الفيديو من الرابط ده.")
                    Text(s.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    Text("لو الفيديو خاص أو محتاج تسجيل دخول، افتحه في متصفح التطبيق وسجّل دخول، وبعدين اضغط «حمّل الفيديو من الصفحة دي».", fontSize = 12.sp)
                }
                // The dialog gives this slot whatever height is left on screen; the column scrolls inside it.
                // (Before, heightIn came after verticalScroll, so the content was cut at 520dp and never scrolled.)
                is Load.Ready -> Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Thumb(s.meta.thumbnail)
                    Text(s.meta.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            s.meta.uploader.takeIf { it.isNotBlank() },
                            s.meta.durationSec.takeIf { it > 0 }?.let { formatDuration(it) },
                            s.meta.site.takeIf { it.isNotBlank() },
                        ).joinToString(" • "),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // the suggestion
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lightbulb, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "مقترح: " + if (s.suggestion.audioOnly) "صوت بس (MP3)" else "فيديو ${s.suggestion.height ?: ""}p",
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(s.suggestion.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                    OptionRow(
                        selected = choice == AUDIO, icon = true, label = "صوت بس — MP3",
                        size = s.meta.audio.approxBytes, recommended = s.suggestion.audioOnly,
                    ) { choice = AUDIO }
                    s.meta.heights.forEach { o ->
                        val h = o.height ?: return@forEach
                        OptionRow(
                            selected = choice == h, icon = false, label = "فيديو ${h}p",
                            size = o.approxBytes, recommended = !s.suggestion.audioOnly && s.suggestion.height == h,
                        ) { choice = h }
                    }
                    Text("الحفظ في:", fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !toVault, onClick = { toVault = false }); Text("التنزيلات")
                        Spacer(Modifier.width(12.dp))
                        RadioButton(selected = toVault, onClick = { toVault = true }); Text("الخزنة 🔒")
                    }
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is Load.Ready -> TextButton(enabled = choice != null, onClick = {
                    val c = choice ?: return@TextButton
                    Downloader.enqueueVideo(req.url, s.meta.title, if (c == AUDIO) null else c, req.headers, toVault)
                    onStarted(toVault)
                }) { Text("ابدأ التحميل") }
                is Load.Failed -> TextButton(onClick = {
                    onFallback(DownloadRequest(req.url, URLUtil.guessFileName(req.url, null, null), headers = req.headers))
                }) { Text("حمّله كملف عادي") }
                Load.Loading -> {}
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun OptionRow(selected: Boolean, icon: Boolean, label: String, size: Long, recommended: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.primaryContainer else scheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (icon) Icons.Filled.AudioFile else Icons.Filled.Movie, null, Modifier.size(22.dp),
                tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = if (selected || recommended) FontWeight.Bold else FontWeight.Normal)
                if (recommended) Text("⭐ المقترح", fontSize = 11.sp, color = scheme.primary)
            }
            if (size > 0) Text("~${formatBytes(size)}", fontSize = 12.sp, color = scheme.onSurfaceVariant)
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

@Composable
private fun Thumb(url: String?) {
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
    val b = bmp ?: return
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
        Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
    }
}
