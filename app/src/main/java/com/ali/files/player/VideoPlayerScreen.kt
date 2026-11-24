package com.ali.files.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot

private val Gold = Color(0xFFD4A94F)

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Just the picture — used inside picture-in-picture and behind the controls. */
@Composable
fun VideoSurface(resize: Int, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                keepScreenOn = true
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                player = PlayerHub.player
            }
        },
        update = { it.resizeMode = resize; if (it.player !== PlayerHub.player) it.player = PlayerHub.player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/**
 * Full-screen video player: gestures (sides = brightness / volume, double tap = ±10 s, pinch = zoom),
 * speed, subtitles, screen lock, rotation, picture-in-picture, keep-playing-in-background.
 */
@Composable
fun VideoPlayerScreen(onPip: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val p = PlayerHub.player
    val track = PlayerHub.current

    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var childLock by remember { mutableStateOf(false) }
    val vctx = androidx.compose.ui.platform.LocalContext.current
    val pickSub = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { vctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            PlayerHub.addSubtitle(u)
        }
    }
    // child lock: the phone stays on this video (screen pinning) until unlocked with the phone's lock
    fun startChildLock() {
        val a = vctx as? android.app.Activity ?: return
        runCatching { a.startLockTask() }
        childLock = true; locked = true
    }
    fun endChildLock() {
        com.ali.files.library.unlockWithPhoneLock(vctx, "افتح قفل الأطفال") {
            runCatching { (vctx as? android.app.Activity)?.stopLockTask() }
            childLock = false; locked = false
        }
    }
    var resize by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var subsMenu by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // full screen: no status / navigation bars while the video is open
    DisposableEffect(Unit) {
        val w = activity?.window
        val c = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        c?.hide(WindowInsetsCompat.Type.systemBars())
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            c?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            w?.let { it.attributes = it.attributes.apply { screenBrightness = -1f } }
        }
    }

    // turn sideways automatically for wide videos
    LaunchedEffect(Unit) {
        repeat(40) {
            val vs = p.videoSize
            if (vs.width > 0 && vs.height > 0) {
                landscape = vs.width > vs.height
                activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                return@LaunchedEffect
            }
            delay(250)
        }
    }

    // progress + auto-hide of the controls
    LaunchedEffect(Unit) {
        while (true) {
            if (dragging == null) position = p.currentPosition
            duration = p.duration.coerceAtLeast(0)
            if (controls && PlayerHub.isPlaying.value && System.currentTimeMillis() - lastTouch > 3500 && !speedMenu && !subsMenu) controls = false
            delay(250)
        }
    }
    LaunchedEffect(seekHint) { if (seekHint != null) { delay(700); seekHint = null } }
    LaunchedEffect(gestureHint) { if (gestureHint != null) { delay(900); gestureHint = null } }

    BackHandler {
        if (locked) return@BackHandler
        PlayerHub.savePosition()
        p.pause()
        PlayerHub.videoOpen.value = false
    }

    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volAcc by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var bright by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f) }

    // gestures work in screen (left/right) terms, so use LTR for this screen
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { lastTouch = System.currentTimeMillis(); controls = !controls },
                        onDoubleTap = { o ->
                            if (locked) return@detectTapGestures
                            lastTouch = System.currentTimeMillis()
                            if (o.x < size.width / 2) { p.seekBack(); seekHint = "‎-10" } else { p.seekForward(); seekHint = "‎+10" }
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val right = down.position.x > size.width / 2
                        var mode = 0 // 0 none, 1 vertical drag, 2 pinch
                        var startDist = 0f
                        do {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                mode = 2
                                val d = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                                if (startDist == 0f) startDist = d
                                else if (d / startDist > 1.2f) { resize = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; gestureHint = "تكبير" }
                                else if (d / startDist < 0.83f) { resize = AspectRatioFrameLayout.RESIZE_MODE_FIT; gestureHint = "الحجم الأصلي" }
                                ev.changes.forEach { it.consume() }
                            } else if (pressed.size == 1 && mode != 2) {
                                val c = pressed[0]
                                val totalDy = c.position.y - down.position.y
                                val totalDx = c.position.x - down.position.x
                                if (mode == 0 && abs(totalDy) > 28 && abs(totalDy) > abs(totalDx) * 1.5f) mode = 1
                                if (mode == 1) {
                                    val dy = (c.previousPosition.y - c.position.y) / size.height
                                    if (right) {
                                        volAcc = (volAcc + dy * maxVol * 1.6f).coerceIn(0f, maxVol.toFloat())
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volAcc.toInt(), 0)
                                        gestureHint = "🔊 ${(volAcc / maxVol * 100).toInt()}%"
                                    } else {
                                        bright = (bright + dy * 1.4f).coerceIn(0.02f, 1f)
                                        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = bright } }
                                        gestureHint = "☀ ${(bright * 100).toInt()}%"
                                    }
                                    c.consume()
                                }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                },
        ) {
            VideoSurface(resize, Modifier.fillMaxSize())

            // gesture feedback in the middle
            (seekHint ?: gestureHint)?.let { h ->
                Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 22.dp, vertical = 12.dp)) {
                    Text(h, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (locked) {
                AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(16.dp)) {
                    RoundButton(Icons.Filled.Lock, if (childLock) "افتح قفل الأطفال" else "فتح القفل") {
                        if (childLock) endChildLock() else locked = false
                        lastTouch = System.currentTimeMillis()
                    }
                }
            } else AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)).safeDrawingPadding()) {
                    // top bar
                    Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { PlayerHub.savePosition(); p.pause(); PlayerHub.videoOpen.value = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = Color.White)
                        }
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                            Text(track?.title ?: "", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Box {
                            IconButton(onClick = { subsMenu = true; lastTouch = System.currentTimeMillis() }) { Icon(Icons.Filled.Subtitles, "الترجمة", tint = Color.White) }
                            SubtitlesMenu(subsMenu, onAddFile = { pickSub.launch(arrayOf("*/*")) }) { subsMenu = false }
                        }
                        IconButton(onClick = {
                            val t = track ?: return@IconButton
                            p.pause()
                            com.ali.files.cast.Cast.castTrack(vctx, t, p.currentPosition)?.let { gestureHint = it }
                        }) { Icon(Icons.Filled.Cast, "اعرض على التلفزيون", tint = if (com.ali.files.cast.Cast.device.value != null) Gold else Color.White) }
                        IconButton(onClick = { PlayerHub.videoBackground.value = !PlayerHub.videoBackground.value; gestureHint = if (PlayerHub.videoBackground.value) "الصوت هيكمل في الخلفية" else "هيقف لما تخرج" }) {
                            Icon(Icons.Filled.Headphones, "الصوت في الخلفية", tint = if (PlayerHub.videoBackground.value) Gold else Color.White)
                        }
                        IconButton(onClick = onPip) { Icon(Icons.Filled.PictureInPictureAlt, "صورة داخل صورة", tint = Color.White) }
                    }

                    // middle: back 10 / play / forward 10
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        if (PlayerHub.queue.value.size > 1) RoundButton(Icons.Filled.SkipPrevious, "السابق", small = true) { p.seekToPreviousMediaItem(); lastTouch = System.currentTimeMillis() }
                        RoundButton(Icons.Filled.Replay10, "رجوع 10 ثواني") { p.seekBack(); lastTouch = System.currentTimeMillis() }
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).background(Gold).clickable { PlayerHub.toggle(); lastTouch = System.currentTimeMillis() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (PlayerHub.isPlaying.value) Icons.Filled.Pause else Icons.Filled.PlayArrow, "تشغيل", tint = Color(0xFF1A1408), modifier = Modifier.size(40.dp))
                        }
                        RoundButton(Icons.Filled.Forward10, "قدام 10 ثواني") { p.seekForward(); lastTouch = System.currentTimeMillis() }
                        if (PlayerHub.queue.value.size > 1) RoundButton(Icons.Filled.SkipNext, "التالي", small = true) { p.seekToNextMediaItem(); lastTouch = System.currentTimeMillis() }
                    }

                    // bottom: seek bar + tools
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatTime((dragging?.toLong()) ?: position), color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = (dragging ?: position.toFloat()).coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                                onValueChange = { dragging = it; lastTouch = System.currentTimeMillis() },
                                onValueChangeFinished = { dragging?.let { p.seekTo(it.toLong()) }; dragging = null },
                                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                                colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            )
                            Text(formatTime(duration), color = Color.White, fontSize = 12.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { locked = true; controls = true; lastTouch = System.currentTimeMillis() }) { Icon(Icons.Filled.LockOpen, "قفل الشاشة", tint = Color.White) }
                            IconButton(onClick = { startChildLock(); controls = true; gestureHint = "قفل الأطفال: مش هيقدر يخرج من الفيديو"; lastTouch = System.currentTimeMillis() }) {
                                Icon(Icons.Filled.ChildCare, "قفل الأطفال", tint = Color.White)
                            }
                            Box {
                                Row(
                                    Modifier.clip(RoundedCornerShape(50)).clickable { speedMenu = true; lastTouch = System.currentTimeMillis() }.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Speed, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("${PlayerHub.speed.value}x".replace(".0x", "x"), color = Color.White, fontSize = 13.sp)
                                }
                                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                                        DropdownMenuItem(
                                            text = { Text("${s}x".replace(".0x", "x"), fontWeight = if (s == PlayerHub.speed.value) FontWeight.Bold else FontWeight.Normal) },
                                            onClick = { PlayerHub.setSpeed(s); speedMenu = false },
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = {
                                resize = when (resize) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                                gestureHint = when (resize) { AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "ملء الشاشة"; AspectRatioFrameLayout.RESIZE_MODE_FILL -> "مط"; else -> "الحجم الأصلي" }
                                lastTouch = System.currentTimeMillis()
                            }) { Icon(Icons.Filled.AspectRatio, "حجم الصورة", tint = Color.White) }
                            IconButton(onClick = {
                                landscape = !landscape
                                activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                lastTouch = System.currentTimeMillis()
                            }) { Icon(Icons.Filled.ScreenRotation, "لف الشاشة", tint = Color.White) }
                        }
                    }
                }
            }

            PlayerHub.error.value?.let { e ->
                Text(e, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(16.dp))
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(if (small) 44.dp else 54.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(if (small) 26.dp else 32.dp)) }
}

/** Lists the subtitle tracks inside the video (if any) and lets the user pick one or turn them off. */
@Composable
private fun SubtitlesMenu(open: Boolean, onAddFile: () -> Unit, onDismiss: () -> Unit) {
    val p = PlayerHub.player
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("➕ ضيف ملف ترجمة (srt / vtt / ass)", color = Gold) }, onClick = { onDismiss(); onAddFile() })
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (groups.isEmpty()) {
            DropdownMenuItem(text = { Text("مفيش ترجمة في الفيديو ده") }, onClick = onDismiss)
        } else {
            DropdownMenuItem(text = { Text("بدون ترجمة") }, onClick = {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                onDismiss()
            })
            groups.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    val name = f.label ?: f.language ?: "ترجمة ${i + 1}"
                    DropdownMenuItem(
                        text = { Text(name, fontWeight = if (g.isTrackSelected(i)) FontWeight.Bold else FontWeight.Normal) },
                        onClick = {
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                .build()
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** Quick check used by the activity: is a video showing right now. */
fun isVideoShowing(): Boolean = PlayerHub.videoOpen.value && PlayerHub.current?.isVideo == true
