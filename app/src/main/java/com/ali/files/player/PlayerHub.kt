package com.ali.files.player

import android.content.ComponentName
import android.content.Context
import android.media.audiofx.Equalizer
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One thing to play: a file in the library, a file on the phone, a vault file, or an online stream. */
data class Track(
    val id: String,
    val uri: Uri,
    val title: String,
    val subtitle: String = "",
    val isVideo: Boolean,
    /** local file / content uri / http url of a picture for the cover */
    val artwork: String? = null,
    /** library path, for favorites */
    val libraryPath: String? = null,
)

/** A stream picked from yt-dlp's formats (see [com.ali.files.download.VideoExtractor.streamFor]). */
data class StreamPick(
    val video: String?,
    val audio: String?,
    val hls: Boolean,
    val headers: Map<String, String>,
)

/**
 * The app's single player (ExoPlayer), shared by the video screen, the music screen, the mini
 * player and the notification / lock-screen controls (through [PlaybackService]).
 */
object PlayerHub {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(app)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { it.addListener(listener) }
    }

    /** What's loaded, in order (index matches the player's playlist). */
    val queue = mutableStateOf<List<Track>>(emptyList())
    val index = mutableIntStateOf(0)
    val current: Track? get() = queue.value.getOrNull(index.intValue)
    val isPlaying = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)

    /** full-screen video player / full music screen open */
    val videoOpen = mutableStateOf(false)
    val musicOpen = mutableStateOf(false)
    /** keep the sound going when leaving the app during a video */
    val videoBackground = mutableStateOf(false)
    val inPip = mutableStateOf(false)

    val speed = mutableStateOf(1f)
    val shuffle = mutableStateOf(false)
    val repeat = mutableIntStateOf(Player.REPEAT_MODE_OFF)

    /** sleep timer: time (ms) it stops at, 0 = off, -1 = at the end of this track */
    val sleepAt = mutableLongStateOf(0L)
    private var sleepJob: Job? = null

    private var controller: ListenableFuture<MediaController>? = null

    fun init(c: Context) { app = c.applicationContext }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying.value = playing
            if (!playing) savePosition()
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                if (sleepAt.longValue == -1L) { player.pause(); sleepAt.longValue = 0L }
            }
            index.intValue = player.currentMediaItemIndex
            restorePosition()
        }
        override fun onPlayerError(e: PlaybackException) {
            error.value = "مقدرتش أشغّل الملف ده (${e.errorCodeName})"
        }
    }

    /** Binds the background service, so music keeps playing with the screen off and shows in the notification. */
    private fun connect() {
        if (controller != null) return
        controller = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    }

    /** subtitle files the user added to a video (by track id) */
    private val extraSubs = HashMap<String, Uri>()

    private fun subConfig(u: Uri, name: String): MediaItem.SubtitleConfiguration {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
            "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
            "ttml", "xml" -> androidx.media3.common.MimeTypes.APPLICATION_TTML
            else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
        }
        return MediaItem.SubtitleConfiguration.Builder(u).setMimeType(mime).setLabel(name.substringBeforeLast('.')).setLanguage("ar")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
    }

    /** a video's own subtitle files next to it (movie.mp4 → movie.srt / movie.ar.srt …) + the ones the user added */
    private fun subsFor(t: Track): List<MediaItem.SubtitleConfiguration> {
        val l = ArrayList<MediaItem.SubtitleConfiguration>()
        extraSubs[t.id]?.let { l += subConfig(it, it.lastPathSegment ?: "ترجمة.srt") }
        if (t.isVideo && t.uri.scheme == "file") runCatching {
            val f = java.io.File(t.uri.path!!)
            f.parentFile?.listFiles()?.filter { s ->
                s.name.startsWith(f.nameWithoutExtension) && s.extension.lowercase() in setOf("srt", "vtt", "ass", "ssa")
            }?.forEach { s -> l += subConfig(Uri.fromFile(s), s.name) }
        }
        return l
    }

    /** Adds a subtitle file (.srt/.vtt/.ass) to the video that's playing, and keeps playing from the same spot. */
    fun addSubtitle(u: Uri) {
        val t = current ?: return
        extraSubs[t.id] = u
        val pos = player.currentPosition
        val i = player.currentMediaItemIndex
        player.replaceMediaItem(i, mediaItem(t))
        player.seekTo(i, pos)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
    }

    private fun mediaItem(t: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(t.uri)
            .setSubtitleConfigurations(subsFor(t))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.subtitle)
                    .setArtworkUri(t.artwork?.let { a -> if (a.startsWith("/")) Uri.fromFile(java.io.File(a)) else Uri.parse(a) })
                    .build(),
            )
            .build()

    /** Plays a list (a folder of songs, the videos of the library…) starting at [start]. */
    fun play(tracks: List<Track>, start: Int = 0) {
        if (tracks.isEmpty()) return
        connect()
        error.value = null
        queue.value = tracks
        index.intValue = start.coerceIn(0, tracks.lastIndex)
        player.setMediaItems(tracks.map { mediaItem(it) }, index.intValue, C.TIME_UNSET)
        player.shuffleModeEnabled = shuffle.value && !tracks[index.intValue].isVideo
        player.prepare()
        restorePosition()
        player.play()
        val t = tracks[index.intValue]
        if (t.isVideo) { videoOpen.value = true; musicOpen.value = false } else { videoOpen.value = false; musicOpen.value = true }
    }

    /** Plays a video / song straight from the internet (no download), using yt-dlp's stream links. */
    fun playStream(t: Track, s: StreamPick) {
        connect()
        error.value = null
        val ds = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(s.headers)
            .also { f -> s.headers["User-Agent"]?.let { f.setUserAgent(it) } }
        val item = mediaItem(t)
        fun progressive(url: String) = ProgressiveMediaSource.Factory(ds).createMediaSource(item.buildUpon().setUri(url).build())
        val source: MediaSource = when {
            s.hls -> HlsMediaSource.Factory(ds).createMediaSource(item.buildUpon().setUri(s.video ?: s.audio).build())
            s.video != null && s.audio != null -> MergingMediaSource(progressive(s.video), progressive(s.audio))
            else -> progressive(s.video ?: s.audio ?: return)
        }
        queue.value = listOf(t)
        index.intValue = 0
        player.setMediaSource(source)
        player.prepare()
        player.play()
        if (t.isVideo) { videoOpen.value = true; musicOpen.value = false } else { videoOpen.value = false; musicOpen.value = true }
    }

    fun toggle() { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }

    fun jumpTo(i: Int) { if (i in queue.value.indices) { player.seekTo(i, 0); player.play() } }

    fun removeAt(i: Int) {
        if (i !in queue.value.indices) return
        player.removeMediaItem(i)
        queue.value = queue.value.toMutableList().also { it.removeAt(i) }
        index.intValue = player.currentMediaItemIndex
        if (queue.value.isEmpty()) stop()
    }

    fun setSpeed(v: Float) { speed.value = v; player.setPlaybackSpeed(v) }
    fun toggleShuffle() { shuffle.value = !shuffle.value; player.shuffleModeEnabled = shuffle.value }
    fun cycleRepeat() {
        val next = when (repeat.intValue) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        repeat.intValue = next
        player.repeatMode = next
    }

    /** Stops after [minutes] (0 = off, -1 = at the end of the current track). */
    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        when {
            minutes == 0 -> sleepAt.longValue = 0
            minutes < 0 -> sleepAt.longValue = -1
            else -> {
                val at = System.currentTimeMillis() + minutes * 60_000L
                sleepAt.longValue = at
                sleepJob = scope.launch {
                    delay(minutes * 60_000L)
                    player.pause()
                    sleepAt.longValue = 0
                }
            }
        }
    }

    fun stop() {
        savePosition()
        player.stop()
        player.clearMediaItems()
        queue.value = emptyList()
        videoOpen.value = false
        musicOpen.value = false
        setSleep(0)
    }

    // ------------------------------------------------------------ continue where you left off

    private val prefs get() = app.getSharedPreferences("player_pos", Context.MODE_PRIVATE)

    fun savePosition() {
        val t = current ?: return
        val d = player.duration
        val p = player.currentPosition
        if (d <= 0 || d < 60_000) return  // short clips always start from the beginning
        val e = prefs.edit()
        if (p > 5_000 && p < d - 15_000) e.putLong(t.id, p) else e.remove(t.id)
        e.apply()
    }

    private fun restorePosition() {
        val t = current ?: return
        val p = prefs.getLong(t.id, 0L)
        if (p > 0) player.seekTo(player.currentMediaItemIndex, p)
    }

    // ------------------------------------------------------------ equalizer

    private var eq: Equalizer? = null
    val eqEnabled = mutableStateOf(false)

    fun equalizer(): Equalizer? {
        val sid = player.audioSessionId
        if (sid == C.AUDIO_SESSION_ID_UNSET) return null
        if (eq == null) eq = runCatching { Equalizer(0, sid).also { it.enabled = eqEnabled.value } }.getOrNull()
        return eq
    }

    fun setEqEnabled(on: Boolean) {
        eqEnabled.value = on
        equalizer()?.enabled = on
    }
}
