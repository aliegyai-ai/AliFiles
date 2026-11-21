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
)

data class Suggestion(val audioOnly: Boolean, val height: Int?, val reason: String)

data class VideoRequest(val url: String, val headers: Map<String, String> = emptyMap())

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
                ready.complete(Unit)
            } catch (e: Throwable) {
                ready.completeExceptionally(e)
                return@launch
            }
            // sites change constantly: keep yt-dlp fresh (every 2 days)
            val sp = app.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - sp.getLong("lastUpdate", 0) > 2 * 24 * 3600_000L) {
                if (runCatching { update() }.isSuccess) sp.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
            }
        }
    }

    suspend fun update(): String = withContext(Dispatchers.IO) {
        ready.await()
        val status = YoutubeDL.getInstance().updateYoutubeDL(app)
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

    private fun headerOptions(req: YoutubeDLRequest, headers: Map<String, String>) {
        headers["Cookie"]?.takeIf { it.isNotBlank() }?.let { req.addOption("--add-header", "Cookie:$it") }
        headers["Referer"]?.takeIf { it.isNotBlank() }?.let { req.addOption("--add-header", "Referer:$it") }
    }

    suspend fun info(r: VideoRequest): VideoMeta = withContext(Dispatchers.IO) {
        ready.await()
        val req = YoutubeDLRequest(r.url)
        req.addOption("--no-playlist")
        headerOptions(req, r.headers)
        val info = try {
            YoutubeDL.getInstance().getInfo(req)
        } catch (first: Exception) {
            // most failures are an outdated extractor: update once and retry
            runCatching { update() }
            YoutubeDL.getInstance().getInfo(req)
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
        req.addOption("-o", File(dir, "%(title).80B.%(ext)s").absolutePath)
        req.addOption("-f", item.ytFormat ?: formatFor(720))
        if (item.audioOnly) {
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "0")
        } else {
            req.addOption("--merge-output-format", "mp4")
        }
        headerOptions(req, item.headers)
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
