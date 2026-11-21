@file:OptIn(ExperimentalMaterial3Api::class)

package com.ali.files.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.ali.files.download.Downloader
import com.ali.files.library.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request

private val Gold = Color(0xFFD4A94F)
private val Ink = Color(0xFF1A1408)

/** Cover picture for a track: embedded art / video frame / online thumbnail. */
@Composable
fun rememberArtwork(t: Track?): Bitmap? {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, t?.id) {
        value = null
        val art = t?.artwork
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    art != null && art.startsWith("http") ->
                        Downloader.client.newCall(Request.Builder().url(art).build()).execute().use { r ->
                            r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    art != null && art.startsWith("/") -> BitmapFactory.decodeFile(art)
                    t != null && t.uri.scheme != "http" && t.uri.scheme != "https" -> {
                        val r = MediaMetadataRetriever()
                        try {
                            if (t.uri.scheme == "content") r.setDataSource(ctx, t.uri) else r.setDataSource(t.uri.path)
                            r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                                ?: if (t.isVideo) r.getFrameAtTime(3_000_000) else null
                        } finally { r.release() }
                    }
                    else -> null
                }
            }.getOrNull()?.let { Library.scaleDown(it, 720) }
        }
    }
    return bmp
}

@Composable
private fun Artwork(bmp: Bitmap?, modifier: Modifier, corner: Int = 24, iconSize: Int = 72) {
    Box(
        modifier.clip(RoundedCornerShape(corner.dp)).background(Brush.linearGradient(listOf(Color(0xFF4A3816), Color(0xFF1C1710)))),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.MusicNote, null, tint = Gold, modifier = Modifier.size(iconSize.dp))
    }
}

// ============================================================ mini player (bottom of every screen)

@Composable
fun MiniPlayer() {
    val t = PlayerHub.current ?: return
    if (PlayerHub.videoOpen.value || PlayerHub.musicOpen.value) return
    val art = rememberArtwork(t)
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(t.id) {
        while (true) {
            val d = PlayerHub.player.duration
            progress = if (d > 0) PlayerHub.player.currentPosition.toFloat() / d else 0f
            delay(500)
        }
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        LinearProgressIndicator(progress = { progress }, color = Gold, trackColor = Color.Transparent, modifier = Modifier.fillMaxWidth().height(2.dp))
        Row(
            Modifier.fillMaxWidth().clickable { if (t.isVideo) PlayerHub.videoOpen.value = true else PlayerHub.musicOpen.value = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(art, Modifier.size(42.dp), corner = 10, iconSize = 22)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (t.subtitle.isNotBlank()) Text(t.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { PlayerHub.toggle() }) {
                Icon(if (PlayerHub.isPlaying.value) Icons.Filled.Pause else Icons.Filled.PlayArrow, "تشغيل", tint = Gold, modifier = Modifier.size(30.dp))
            }
            if (PlayerHub.queue.value.size > 1) IconButton(onClick = { PlayerHub.player.seekToNextMediaItem() }) { Icon(Icons.Filled.SkipNext, "التالي") }
            IconButton(onClick = { PlayerHub.stop() }) { Icon(Icons.Filled.Close, "إيقاف", Modifier.size(20.dp)) }
        }
    }
}

// ============================================================ full music screen

@Composable
fun NowPlayingScreen() {
    val t = PlayerHub.current
    if (t == null) { LaunchedEffect(Unit) { PlayerHub.musicOpen.value = false }; return }
    val p = PlayerHub.player
    val art = rememberArtwork(t)
    val favs by Library.favorites.collectAsState()
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    BackHandler { PlayerHub.musicOpen.value = false }
    LaunchedEffect(Unit) {
        while (true) {
            if (dragging == null) position = p.currentPosition
            duration = p.duration.coerceAtLeast(0)
            delay(300)
        }
    }

    // the screen below must not get taps through this one
    Box(Modifier.fillMaxSize().background(Color(0xFF0E0D0B)).pointerInput(Unit) { detectTapGestures { } }) {
        // blurred cover as the background (Android 12+)
        if (art != null && Build.VERSION.SDK_INT >= 31) {
            Image(art.asImageBitmap(), null, Modifier.fillMaxSize().blur(60.dp), contentScale = ContentScale.Crop, alpha = 0.45f)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x660E0D0B), Color(0xF20E0D0B)))))

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { PlayerHub.musicOpen.value = false }) { Icon(Icons.Filled.KeyboardArrowDown, "تصغير", tint = Color.White, modifier = Modifier.size(30.dp)) }
                Text(
                    if (PlayerHub.queue.value.size > 1) "${PlayerHub.index.intValue + 1} من ${PlayerHub.queue.value.size}" else "بيشتغل دلوقتي",
                    color = Color(0xFFA39A88), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "القايمة", tint = Color.White) }
            }

            Spacer(Modifier.height(18.dp))
            Artwork(art, Modifier.fillMaxWidth(0.86f).aspectRatio(1f))
            Spacer(Modifier.height(26.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (t.subtitle.isNotBlank()) Text(t.subtitle, color = Color(0xFFA39A88), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                t.libraryPath?.let { path ->
                    val fav = path in favs
                    IconButton(onClick = { Library.toggleFavorite(path) }) {
                        Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "المفضلة", tint = Gold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Slider(
                value = (dragging ?: position.toFloat()).coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { p.seekTo(it.toLong()) }; dragging = null },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color(0xFF3A3328)),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(dragging?.toLong() ?: position), color = Color(0xFFA39A88), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(formatTime(duration), color = Color(0xFFA39A88), fontSize = 12.sp)
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { PlayerHub.toggleShuffle() }) {
                    Icon(Icons.Filled.Shuffle, "عشوائي", tint = if (PlayerHub.shuffle.value) Gold else Color(0xFFA39A88))
                }
                IconButton(onClick = { if (p.currentPosition > 4000) p.seekTo(0) else p.seekToPreviousMediaItem() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "السابق", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Gold).clickable { PlayerHub.toggle() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (PlayerHub.isPlaying.value) Icons.Filled.Pause else Icons.Filled.PlayArrow, "تشغيل", tint = Ink, modifier = Modifier.size(42.dp)) }
                IconButton(onClick = { p.seekToNextMediaItem() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, "التالي", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { PlayerHub.cycleRepeat() }) {
                    Icon(
                        if (PlayerHub.repeat.intValue == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "تكرار",
                        tint = if (PlayerHub.repeat.intValue != Player.REPEAT_MODE_OFF) Gold else Color(0xFFA39A88),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                Box {
                    ToolButton(Icons.Filled.Speed, "${PlayerHub.speed.value}x".replace(".0x", "x"), PlayerHub.speed.value != 1f) { speedMenu = true }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                            DropdownMenuItem(text = { Text("${s}x".replace(".0x", "x")) }, onClick = { PlayerHub.setSpeed(s); speedMenu = false })
                        }
                    }
                }
                Box {
                    val sleep = PlayerHub.sleepAt.longValue
                    ToolButton(
                        Icons.Filled.Bedtime,
                        when {
                            sleep == -1L -> "آخر المقطع"
                            sleep > 0 -> "${((sleep - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} د"
                            else -> "مؤقت"
                        },
                        sleep != 0L,
                    ) { sleepMenu = true }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        listOf(15 to "بعد 15 دقيقة", 30 to "بعد 30 دقيقة", 45 to "بعد 45 دقيقة", 60 to "بعد ساعة", -1 to "في آخر المقطع ده", 0 to "إلغاء المؤقت").forEach { (m, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { PlayerHub.setSleep(m); sleepMenu = false })
                        }
                    }
                }
                ToolButton(Icons.Filled.Equalizer, "المعادل", PlayerHub.eqEnabled.value) { showEq = true }
                ToolButton(Icons.AutoMirrored.Filled.QueueMusic, "القايمة", false) { showQueue = true }
            }
            PlayerHub.error.value?.let { Text(it, color = Color(0xFFE0715A), fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp)) }
        }
    }

    if (showEq) EqualizerSheet { showEq = false }
    if (showQueue) QueueSheet { showQueue = false }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (active) Gold else Color(0xFFA39A88), modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = if (active) Gold else Color(0xFFA39A88))
    }
}

@Composable
private fun EqualizerSheet(onDismiss: () -> Unit) {
    val eq = remember { PlayerHub.equalizer() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("معادل الصوت", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Switch(checked = PlayerHub.eqEnabled.value, onCheckedChange = { PlayerHub.setEqEnabled(it) })
            }
            if (eq == null) {
                Text("المعادل مش متاح على الموبايل ده، أو شغّل حاجة الأول.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else EqualizerControls(eq)
        }
    }
}

@Composable
private fun EqualizerControls(eq: android.media.audiofx.Equalizer) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var preset by remember { mutableIntStateOf(-1) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val count = runCatching { eq.numberOfPresets.toInt() }.getOrDefault(0)
                Column(Modifier.fillMaxWidth()) {
                    (0 until count).chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { i ->
                                FilterChip(
                                    selected = preset == i,
                                    onClick = { runCatching { eq.usePreset(i.toShort()) }; preset = i; PlayerHub.setEqEnabled(true) },
                                    label = { Text(presetName(runCatching { eq.getPresetName(i.toShort()) }.getOrDefault("$i")), fontSize = 12.sp) },
                                )
                            }
                        }
                    }
                }
            }
            val bands = runCatching { eq.numberOfBands.toInt() }.getOrDefault(0)
            val range = runCatching { eq.bandLevelRange }.getOrNull()
            if (range != null) {
                for (b in 0 until bands) {
                    var level by remember(b, preset) { mutableFloatStateOf(runCatching { eq.getBandLevel(b.toShort()).toFloat() }.getOrDefault(0f)) }
                    val hz = runCatching { eq.getCenterFreq(b.toShort()) / 1000 }.getOrDefault(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", fontSize = 12.sp, modifier = Modifier.width(44.dp))
                        Slider(
                            value = level,
                            onValueChange = {
                                level = it
                                runCatching { eq.setBandLevel(b.toShort(), it.toInt().toShort()) }
                                PlayerHub.setEqEnabled(true)
                            },
                            valueRange = range[0].toFloat()..range[1].toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                        Text("${(level / 100).toInt()} dB", fontSize = 11.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                    }
                }
            }
    }
}

private fun presetName(n: String): String = when (n.lowercase()) {
    "normal" -> "عادي"; "classical" -> "كلاسيك"; "dance" -> "رقص"; "flat" -> "مسطّح"; "folk" -> "شعبي"
    "heavy metal" -> "ميتال"; "hip hop" -> "هيب هوب"; "jazz" -> "جاز"; "pop" -> "بوب"; "rock" -> "روك"
    else -> n
}

@Composable
private fun QueueSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("قايمة التشغيل (${PlayerHub.queue.value.size})", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(bottom = 20.dp)) {
            itemsIndexed(PlayerHub.queue.value, key = { i, t -> "$i-${t.id}" }) { i, t ->
                val current = i == PlayerHub.index.intValue
                Row(
                    Modifier.fillMaxWidth().clickable { PlayerHub.jumpTo(i) }
                        .background(if (current) Gold.copy(alpha = 0.12f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", fontSize = 12.sp, color = if (current) Gold else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, color = if (current) Gold else MaterialTheme.colorScheme.onSurface)
                        if (t.subtitle.isNotBlank()) Text(t.subtitle, fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!current) IconButton(onClick = { PlayerHub.removeAt(i) }) { Icon(Icons.Filled.Close, "شيل", Modifier.size(18.dp)) }
                }
            }
        }
    }
}

