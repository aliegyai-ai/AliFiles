package com.ali.files.download

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** One quality the user can pick. [height] = null means audio only. */
data class VideoOption(val height: Int?, val approxBytes: Long)

/** What we show in the quality dialog, extracted from yt-dlp's info JSON. */
data class VideoMeta(
    val url: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnail: String?,
    val site: String,
    val categories: List<String>,
    val tags: List<String>,
    val heights: List<VideoOption>,
    val audio: VideoOption,
    /** direct links yt-dlp found, for playing online without downloading */
    val streams: List<StreamFormat> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
)

data class StreamFormat(
    val url: String,
    val height: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val ext: String,
    val vcodec: String,
    val bitrate: Int,
    val hls: Boolean,
    val headers: Map<String, String>,
)

data class Suggestion(val audioOnly: Boolean, val height: Int?, val reason: String)

data class VideoRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** what the page already tells us — shown at once while the qualities load */
    val title: String? = null,
    val thumbnail: String? = null,
    /** from a private tab → saved in the vault by default */
    val private: Boolean = false,
)

/**
 * Wraps yt-dlp (via youtubedl-android) — supports YouTube, Facebook, TikTok, Instagram, X
 * and 1000+ other sites. Also makes the audio-or-video suggestion.
 */
object VideoExtractor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    private lateinit var app: Context
    @Volatile var lastUpdateResult: String? = null
        private set

    fun init(c: Context) {
        app = c.applicationContext
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
            } catch (e: Throwable) {
                ready.completeExceptionally(e)
                return@launch
            }
            // The yt-dlp bundled in the library is old. YouTube now needs a version that can run
            // its JavaScript challenges (2025.11.12+), so update right away before the first use.
            val sp = app.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
            if (!supportsJsRuntime()) {
                if (runCatching { doUpdate() }.isSuccess) sp.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
            }
            ready.complete(Unit)
            // sites change constantly: keep yt-dlp fresh (every 2 days)
            if (System.currentTimeMillis() - sp.getLong("lastUpdate", 0) > 2 * 24 * 3600_000L) {
                if (runCatching { update() }.isSuccess) sp.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
            }
        }
    }

    /** yt-dlp version string like "2026.06.13", or null if it can't be read. */
    private fun ytdlpVersion(): String? = runCatching { YoutubeDL.getInstance().version(app) }.getOrNull()

    /** 2025.11.12 is the first yt-dlp that can use an external JavaScript runtime (needed for YouTube). */
    private fun supportsJsRuntime(): Boolean = (ytdlpVersion() ?: "0") >= "2025.11.12"

    /** QuickJS, shipped inside the APK as a native "library" so Android lets us run it. */
    private val quickJs: File? get() = File(app.applicationInfo.nativeLibraryDir, "libqjs.so").takeIf { it.exists() }

    /** Lets yt-dlp solve YouTube's JavaScript challenges with QuickJS — without it some qualities go missing. */
    @Volatile private var jsBroken = false

    private fun jsRuntimeOptions(req: YoutubeDLRequest) {
        if (jsBroken) return
        val qjs = quickJs ?: return
        if (!supportsJsRuntime()) return
        req.addOption("--js-runtimes", "quickjs:${qjs.absolutePath}")
        req.addOption("--remote-components", "ejs:github")
    }

    private fun doUpdate() = YoutubeDL.getInstance().updateYoutubeDL(app)

    /** Waits until yt-dlp and FFmpeg are unpacked (FFmpeg is also used to cut / join audio). */
    suspend fun awaitReady() = ready.await()

    suspend fun update(): String = withContext(Dispatchers.IO) {
        ready.await()
        val status = doUpdate()
        val msg = when (status?.name) {
            "DONE" -> "اتحدث محرك الفيديو لآخر إصدار ✅"
            "ALREADY_UP_TO_DATE" -> "محرك الفيديو محدث أصلاً ✅"
            else -> "تم"
        }
        lastUpdateResult = msg
        msg
    }

    private val siteHosts = listOf(
        "youtube.com", "youtu.be", "facebook.com", "fb.watch", "fb.com", "tiktok.com", "instagram.com",
        "twitter.com", "x.com", "vimeo.com", "dailymotion.com", "soundcloud.com", "reddit.com",
        "twitch.tv", "pinterest.com", "linkedin.com", "snapchat.com", "threads.net", "bilibili.com",
        "ok.ru", "vk.com", "telegram.org", "t.me", "streamable.com", "rumble.com",
    )

    /** True for pages yt-dlp is known to handle (we try others too, on request). */
    fun isVideoPage(url: String): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host?.lowercase(Locale.US) }.getOrNull() ?: return false
        return siteHosts.any { host == it || host.endsWith(".$it") }
    }

    /** yt-dlp keeps YouTube's player code and solved challenges here, so the next video is much faster. */
    private fun cacheOptions(req: YoutubeDLRequest) {
        req.addOption("--cache-dir", File(app.cacheDir, "ytdlp-cache").apply { mkdirs() }.absolutePath)
        // through Tor while it's on
        com.ali.files.browser.TorManager.ytdlpProxy()?.let { req.addOption("--proxy", it) }
    }

    // ------------------------------------------------------------ remembering / reading ahead

    private val infoCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, VideoMeta>>()
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<VideoMeta>>()
    private const val CACHE_MS = 30 * 60_000L
    @Volatile private var prefetching = false
    @Volatile private var nextPrefetch: VideoRequest? = null

    /** One key per video (YouTube links come with many different extra parameters). */
    fun keyOf(url: String): String {
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return url
        val host = u.host?.lowercase(Locale.US) ?: return url
        if (host.endsWith("youtube.com") || host == "youtu.be") {
            youtubeId(url)?.let { return "yt:$it" }
        }
        return url.substringBefore('#')
    }

    fun youtubeId(url: String): String? {
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase(Locale.US) ?: return null
        return when {
            host == "youtu.be" -> u.pathSegments.firstOrNull()
            !host.endsWith("youtube.com") -> null
            u.getQueryParameter("v") != null -> u.getQueryParameter("v")
            u.pathSegments.size >= 2 && u.pathSegments[0] in setOf("shorts", "live", "embed") -> u.pathSegments[1]
            else -> null
        }?.takeIf { it.length in 6..20 }
    }

    /** A page that shows one video (not a feed / home page) — worth reading ahead. */
    fun isSingleVideoPage(url: String): Boolean {
        if (!isVideoPage(url)) return false
        if (youtubeId(url) != null) return true
        val p = url.lowercase(Locale.US)
        return listOf("/video/", "/videos/", "/reel/", "/reels/", "/watch", "/status/", "/p/", "/v/", "/clip", "fb.watch/", "vm.tiktok.com", "vt.tiktok.com")
            .any { p.contains(it) }
    }

    fun cached(r: VideoRequest): VideoMeta? =
        infoCache[keyOf(r.url)]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.second

    /** Reads a video's qualities in the background, so they're ready when the user taps download. */
    fun prefetch(r: VideoRequest) {
        val key = keyOf(r.url)
        if (cached(r) != null || inflight.containsKey(key)) return
        synchronized(this) {
            if (prefetching) { nextPrefetch = r; return }
            prefetching = true
        }
        scope.launch {
            var req: VideoRequest? = r
            while (req != null) {
                runCatching { info(req) }
                req = synchronized(this@VideoExtractor) {
                    val n = nextPrefetch; nextPrefetch = null
                    if (n == null) prefetching = false
                    n
                }
            }
        }
    }

    /** Video details: from memory if read in the last 30 minutes, else from yt-dlp (one run per video at a time). */
    suspend fun info(r: VideoRequest): VideoMeta {
        cached(r)?.let { return it }
        val key = keyOf(r.url)
        val d = inflight.getOrPut(key) { scope.async { fetchInfo(r) } }
        try {
            val m = d.await()
            infoCache[key] = System.currentTimeMillis() to m
            return m
        } finally {
            if (d.isCompleted) inflight.remove(key, d)
        }
    }

    // ---------------------------------------------------------------- playlists & channels

    data class PlaylistEntry(val url: String, val title: String, val durationSec: Long, val thumbnail: String?)
    data class PlaylistInfo(val title: String, val entries: List<PlaylistEntry>)

    /** a link to a whole list: a YouTube playlist (without a single video), a channel, a SoundCloud set */
    fun isPlaylistOnly(url: String): Boolean {
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val h = u.host ?: return false
        val p = u.path ?: ""
        return when {
            "youtube" in h -> (p.startsWith("/playlist") && u.getQueryParameter("list") != null) ||
                p.startsWith("/@") || p.startsWith("/channel/") || p.startsWith("/c/") || p.startsWith("/user/")
            "soundcloud" in h -> "/sets/" in p
            else -> false
        }
    }

    /** a single video that's also in a list (watch?v=…&list=…) */
    fun playlistOf(url: String): String? {
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val list = u.getQueryParameter("list") ?: return null
        if (list.startsWith("RD") || list == "WL" || list == "LL") return null // YouTube mixes: endless
        return "https://www.youtube.com/playlist?list=$list"
    }

    /** Reads the list (titles, lengths, pictures) without downloading anything. Channels: the newest [max]. */
    suspend fun playlist(url: String, headers: Map<String, String>, max: Int = 300): PlaylistInfo = withContext(Dispatchers.IO) {
        ready.await()
        val channel = isPlaylistOnly(url) && runCatching { android.net.Uri.parse(url).path ?: "" }.getOrDefault("").let { it.startsWith("/@") || it.startsWith("/channel/") || it.startsWith("/c/") || it.startsWith("/user/") }
        val target = if (channel && !url.trimEnd('/').endsWith("/videos")) url.trimEnd('/') + "/videos" else url
        val req = YoutubeDLRequest(target)
        req.addOption("--flat-playlist")
        req.addOption("-J")
        req.addOption("--playlist-end", (if (channel) minOf(max, 30) else max).toString())
        cacheOptions(req)
        headerOptions(req, headers)
        val out = try { YoutubeDL.getInstance().execute(req).out } catch (e: Exception) { runCatching { update() }; YoutubeDL.getInstance().execute(req).out }
        val j = org.json.JSONObject(out)
        val arr = j.optJSONArray("entries") ?: org.json.JSONArray()
        val entries = (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optString("id")
            val link = e.optString("url").takeIf { it.startsWith("http") }
                ?: if (id.isNotBlank() && ("youtube" in target || "youtu.be" in target)) "https://www.youtube.com/watch?v=$id" else e.optString("webpage_url")
            if (link.isNullOrBlank()) return@mapNotNull null
            val title = e.optString("title").ifBlank { "فيديو ${i + 1}" }
            if (title == "[Private video]" || title == "[Deleted video]") return@mapNotNull null
            val thumbs = e.optJSONArray("thumbnails")
            val thumb = thumbs?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                ?: if ("youtube" in target && id.isNotBlank()) "https://i.ytimg.com/vi/$id/mqdefault.jpg" else null
            PlaylistEntry(link, title, e.optDouble("duration", 0.0).toLong(), thumb)
        }
        PlaylistInfo(j.optString("title").ifBlank { "قايمة تشغيل" }, entries)
    }

    private fun headerOptions(req: YoutubeDLRequest, headers: Map<String, String>) {
        headers["Cookie"]?.takeIf { it.isNotBlank() }?.let { req.addOption("--add-header", "Cookie:$it") }
        headers["Referer"]?.takeIf { it.isNotBlank() }?.let { req.addOption("--add-header", "Referer:$it") }
    }

    private suspend fun fetchInfo(r: VideoRequest): VideoMeta = withContext(Dispatchers.IO) {
        ready.await()
        fun request(withJs: Boolean) = YoutubeDLRequest(r.url).also { req ->
            req.addOption("--no-playlist")
            cacheOptions(req)
            headerOptions(req, r.headers)
            if (withJs) jsRuntimeOptions(req)
        }
        val info = try {
            YoutubeDL.getInstance().getInfo(request(withJs = true))
        } catch (first: Exception) {
            // most failures are an outdated extractor: update once and retry
            // (and without QuickJS, in case that was the part that failed)
            runCatching { update() }
            try {
                YoutubeDL.getInstance().getInfo(request(withJs = true))
            } catch (second: Exception) {
                if (quickJs == null || jsBroken) throw second
                // works without QuickJS → QuickJS is the problem on this phone; stop using it this session
                YoutubeDL.getInstance().getInfo(request(withJs = false)).also { jsBroken = true }
            }
        }
        val duration = (info.duration ?: 0).toLong()
        val formats = info.formats ?: arrayListOf()

        fun size(f: com.yausername.youtubedl_android.mapper.VideoFormat): Long {
            val s = f.fileSize ?: 0L
            if (s > 0) return s
            val a = f.fileSizeApproximate ?: 0L
            if (a > 0) return a
            val tbr = f.tbr ?: 0
            return if (tbr > 0 && duration > 0) tbr.toLong() * 1000 / 8 * duration else 0L
        }

        val audioFormats = formats.filter { (it.vcodec ?: "") == "none" && (it.acodec ?: "none") != "none" }
        val audioBytes = audioFormats.maxOfOrNull { size(it) } ?: 0L
        val byHeight = formats
            .filter { (it.height ?: 0) > 0 && (it.vcodec ?: "none") != "none" }
            .groupBy { it.height ?: 0 }
            .map { (h, fs) ->
                val v = fs.maxOf { size(it) }
                val hasAudio = fs.any { (it.acodec ?: "none") != "none" }
                VideoOption(h, if (v > 0) v + (if (hasAudio) 0 else audioBytes) else 0L)
            }
            .sortedByDescending { it.height }
            .let { list -> list.filter { (it.height ?: 0) in setOf(2160, 1440, 1080, 720, 480, 360, 240) }.ifEmpty { list.take(5) } }

        VideoMeta(
            url = r.url,
            title = info.title ?: "video",
            uploader = info.uploader ?: "",
            durationSec = duration,
            thumbnail = info.thumbnail,
            site = info.extractorKey ?: info.extractor ?: "",
            categories = info.categories?.toList() ?: emptyList(),
            tags = info.tags?.toList() ?: emptyList(),
            heights = byHeight,
            audio = VideoOption(null, audioBytes),
            streams = formats.mapNotNull { f ->
                val u = f.url ?: return@mapNotNull null
                if (!u.startsWith("http")) return@mapNotNull null
                StreamFormat(
                    url = u,
                    height = f.height,
                    hasVideo = (f.vcodec ?: "none") != "none" && f.height > 0,
                    hasAudio = (f.acodec ?: "none") != "none",
                    ext = f.ext ?: "",
                    vcodec = f.vcodec ?: "",
                    bitrate = maxOf(f.tbr, f.abr),
                    hls = u.contains(".m3u8") || (f.formatId ?: "").startsWith("hls"),
                    headers = f.httpHeaders ?: emptyMap(),
                )
            },
            headers = (info.httpHeaders ?: emptyMap()) + r.headers.filterKeys { it == "Cookie" || it == "Referer" },
        )
    }

    // ------------------------------------------------------------ the suggestion

    private val audioWords = listOf(
        "official audio", "audio only", "lyrics", "lyric", "كلمات", "song", "أغنية", "اغنية", "اغاني", "أغاني",
        "نشيد", "أنشودة", "انشودة", "مهرجان", "موسيقى", "music", "قرآن", "القرآن", "قران", "تلاوة", "سورة",
        "تفسير", "ابتهال", "أذكار", "اذكار", "دعاء", "رقية", "بودكاست", "podcast", "محاضرة", "خطبة", "درس",
        "lecture", "audiobook", "كتاب مسموع", "راديو", "radio", "مقرئ", "شيخ", "playlist",
    )
    private val videoWords = listOf(
        "official video", "music video", "كليب", "clip", "فيلم", "film", "movie", "trailer", "اعلان", "إعلان",
        "شرح عملي", "tutorial", "how to", "طريقة", "مباراة", "ملخص", "هدف", "goals", "vlog", "تجربة", "review",
        "مراجعة", "unboxing", "مسلسل", "حلقة",
    )

    fun suggest(m: VideoMeta): Suggestion {
        val text = (m.title + " " + m.tags.joinToString(" ") + " " + m.categories.joinToString(" ")).lowercase(Locale.ROOT)
        val host = runCatching { android.net.Uri.parse(m.url).host ?: "" }.getOrDefault("")
        val videoHit = videoWords.firstOrNull { text.contains(it) }
        val audioHit = audioWords.firstOrNull { text.contains(it) }
        val best = m.heights.firstOrNull()?.height

        fun videoPick(reason: String): Suggestion {
            val h = when {
                m.durationSec in 1..180 -> m.heights.firstOrNull { (it.height ?: 0) <= 1080 }?.height ?: best
                else -> m.heights.firstOrNull { (it.height ?: 0) <= 720 }?.height ?: best
            }
            return Suggestion(false, h, reason)
        }

        return when {
            m.heights.isEmpty() -> Suggestion(true, null, "الرابط ده فيه صوت بس")
            host.contains("music.youtube") || host.contains("soundcloud") ->
                Suggestion(true, null, "ده من موقع موسيقى، فالصورة مش هتفرق")
            m.uploader.endsWith(" - Topic") ->
                Suggestion(true, null, "ده من قناة موسيقى رسمية (صورة ثابتة)")
            videoHit != null ->
                videoPick("شكله محتوى الصورة فيه مهمة («$videoHit»)")
            audioHit != null ->
                Suggestion(true, null, "شكله محتوى للسمع («$audioHit»)، والصوت أخف بكتير في الحجم")
            m.categories.any { it.equals("Music", true) } ->
                Suggestion(true, null, "تصنيفه «موسيقى»")
            m.durationSec > 45 * 60 ->
                Suggestion(true, null, "طويل (${m.durationSec / 60} دقيقة) — غالباً محاضرة أو بودكاست، والصوت هيوفر مساحة كبيرة")
            m.durationSec in 1..180 ->
                videoPick("مقطع قصير، فجودة عالية مش هتفرق كتير في الحجم")
            else -> videoPick("فيديو عادي — 720p مناسبة لشاشة الموبايل وحجمها معقول")
        }
    }

    // ------------------------------------------------------------ play online (no download)

    /**
     * Picks what to stream for the chosen quality ([height] null = sound only):
     * one file with picture+sound if there is one, else an HLS stream, else picture + sound joined in the player.
     */
    fun streamFor(m: VideoMeta, height: Int?): com.ali.files.player.StreamPick? {
        val s = m.streams
        fun hdr(f: StreamFormat?) = m.headers + (f?.headers ?: emptyMap())
        val bestAudio = s.filter { it.hasAudio && !it.hasVideo && !it.hls }
            .sortedWith(compareByDescending<StreamFormat> { it.ext == "m4a" }.thenByDescending { it.bitrate })
            .firstOrNull()
        if (height == null) {
            val a = bestAudio ?: s.filter { it.hasAudio && !it.hls }.minByOrNull { it.height } ?: s.firstOrNull { it.hls && it.hasAudio }
            return a?.let { com.ali.files.player.StreamPick(video = null, audio = it.url, hls = it.hls, headers = hdr(it)) }
        }
        val fit = { f: StreamFormat -> f.hasVideo && f.height <= height }
        s.filter { fit(it) && it.hasAudio && !it.hls }.maxByOrNull { it.height }?.let { p ->
            // a full file is only good if it isn't much worse than what was asked for
            if (p.height >= minOf(height, 480)) return com.ali.files.player.StreamPick(p.url, null, false, hdr(p))
        }
        s.filter { fit(it) && it.hls }.maxByOrNull { it.height }?.let { h ->
            return com.ali.files.player.StreamPick(h.url, null, true, hdr(h))
        }
        val v = s.filter { fit(it) && !it.hasAudio && !it.hls }
            .sortedWith(compareByDescending<StreamFormat> { it.height }.thenByDescending { it.vcodec.startsWith("avc") }.thenByDescending { it.ext == "mp4" })
            .firstOrNull()
        if (v != null && bestAudio != null) return com.ali.files.player.StreamPick(v.url, bestAudio.url, false, hdr(v))
        val any = s.filter { it.hasVideo && it.hasAudio }.maxByOrNull { it.height } ?: return null
        return com.ali.files.player.StreamPick(any.url, null, any.hls, hdr(any))
    }

    // ------------------------------------------------------------ download

    fun formatFor(height: Int?): String =
        if (height == null) "ba/b"
        else "bv*[height<=$height][ext=mp4]+ba[ext=m4a]/bv*[height<=$height]+ba/b[height<=$height]/b"

    private val sizeRe = Regex("of\\s+~?\\s*([\\d.]+)\\s*(B|KiB|MiB|GiB)")
    private val speedRe = Regex("at\\s+([\\d.]+)\\s*(B|KiB|MiB|GiB)/s")

    private fun bytes(v: String, unit: String): Long {
        val d = v.toDoubleOrNull() ?: return 0
        val m = when (unit) { "KiB" -> 1024.0; "MiB" -> 1024.0 * 1024; "GiB" -> 1024.0 * 1024 * 1024; else -> 1.0 }
        return (d * m).toLong()
    }

    /**
     * Runs yt-dlp into [dir]. [onProgress] gets (percent 0-100, total bytes or -1, speed bytes/s, note).
     * Returns the finished file.
     */
    fun download(item: DownloadItem, dir: File, onProgress: (Float, Long, Long, String?) -> Unit): File {
        kotlinx.coroutines.runBlocking { ready.await() }
        val req = YoutubeDLRequest(item.url)
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        cacheOptions(req)
        req.addOption("-o", File(dir, "%(title).80B.%(ext)s").absolutePath)
        req.addOption("-f", item.ytFormat ?: formatFor(720))
        if (item.audioOnly) {
            req.addOption("-x")
            req.addOption("--audio-format", item.audioFormat.ifBlank { "mp3" })
            req.addOption("--audio-quality", if (item.audioKbps > 0) "${item.audioKbps}K" else "0")
            // speech: one channel is enough and half the size
            if (item.audioMono) req.addOption("--postprocessor-args", "ExtractAudio:-ac 1")
        } else {
            req.addOption("--merge-output-format", "mp4")
        }
        headerOptions(req, item.headers)
        jsRuntimeOptions(req)
        // yt-dlp downloads the picture and the sound as two separate streams, and its percent restarts
        // from 0 for each one. Combine them into one steady progress bar: picture 0-85%, sound 85-100%.
        var stream = 0
        var firstTotal = 0L
        var lastTotal = -1L
        YoutubeDL.getInstance().execute(req, item.id.toString()) { progress, _, line ->
            if (line.contains("[download] Destination:")) {
                if (stream == 1 && lastTotal > 0) firstTotal = lastTotal
                stream++
            }
            val total = sizeRe.find(line)?.let { bytes(it.groupValues[1], it.groupValues[2]) } ?: -1L
            if (total > 0) lastTotal = total
            val speed = speedRe.find(line)?.let { bytes(it.groupValues[1], it.groupValues[2]) } ?: 0L
            val twoStreams = !item.audioOnly && stream >= 2
            val p = progress.coerceIn(0f, 100f)
            val overall = when {
                item.audioOnly || stream <= 1 -> if (item.audioOnly) p else p * 0.85f
                else -> 85f + p * 0.15f
            }
            val combinedTotal = when {
                twoStreams && firstTotal > 0 && total > 0 -> firstTotal + total
                stream <= 1 && total > 0 -> total
                else -> -1L
            }
            val note = when {
                line.contains("[Merger]") -> "جاري دمج الصوت والصورة…"
                line.contains("[ExtractAudio]") -> "جاري التحويل لـ MP3…"
                item.audioOnly -> "جاري تحميل الصوت…"
                twoStreams -> "جاري تحميل الصوت (2/2)…"
                else -> "جاري تحميل الصورة (1/2)…"
            }
            onProgress(overall, combinedTotal, speed, note)
        }
        return dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.contains(".temp") }
            ?.maxByOrNull { it.length() }
            ?: throw java.io.IOException("التحميل خلص بس الملف مش موجود")
    }

    fun cancel(id: Long) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id.toString()) }
    }
}
