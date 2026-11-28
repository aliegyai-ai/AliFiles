package com.ali.files.download

import kotlinx.serialization.Serializable

/** Audio-only quality: smaller files for less space. [m4a] = same quality at a lower bitrate. */
enum class AudioQ(val label: String, val mp3: Int, val m4a: Int, val mono: Boolean, val hint: String) {
    HIGH("عالية", 320, 256, false, "سماعات كويسة"),
    NORMAL("عادية", 128, 96, false, "أغلب الأغاني"),
    SAVER("موفّرة", 64, 48, false, "لو المساحة قليلة"),
    VOICE("كلام", 32, 32, true, "قرآن ودروس وبودكاست");

    fun kbps(format: String) = if (format == "m4a") m4a else mp3
    /** about how big: bitrate × length */
    fun bytes(format: String, seconds: Long) = kbps(format) * 1000L / 8 * seconds
}

@Serializable
enum class DStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

/** A byte range of the file. [end] is inclusive, -1 when the total size is unknown. */
@Serializable
data class Segment(val start: Long, val end: Long, val done: Long = 0)

@Serializable
data class DownloadItem(
    val id: Long,
    val url: String,
    val fileName: String,
    val mime: String? = null,
    val total: Long = -1,
    val downloaded: Long = 0,
    val status: DStatus = DStatus.QUEUED,
    val threads: Int = 4,
    val headers: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    val savedUri: String? = null,
    /** where the file is in the app's library (new downloads stay inside the app) */
    val libraryPath: String? = null,
    val resumable: Boolean = false,
    val segments: List<Segment> = emptyList(),
    val hls: Boolean = false,
    val hlsIndex: Int = 0,
    val hlsTotal: Int = 0,
    val speed: Long = 0,
    val ytdlp: Boolean = false,
    val ytFormat: String? = null,
    val audioOnly: Boolean = false,
    /** audio-only downloads: bitrate (0 = best), "mp3" / "m4a", one channel (speech) */
    val audioKbps: Int = 0,
    val audioFormat: String = "mp3",
    val audioMono: Boolean = false,
    /** from a playlist: the library folder it goes in, and the playlist (in the app) it joins */
    val libFolder: String? = null,
    val group: String? = null,
    val note: String? = null,
    /** «حمّل بعدين»: when it should start (ms), 0 = not scheduled */
    val scheduledAt: Long = 0,
    /** the picture of the video (from the page), saved as the file's cover when it's done */
    val thumbUrl: String? = null,
) {
    val fraction: Float
        get() = when {
            hls && hlsTotal > 0 -> hlsIndex.toFloat() / hlsTotal
            total > 0 -> (downloaded.toFloat() / total).coerceIn(0f, 1f)
            else -> -1f
        }
    val active: Boolean get() = status == DStatus.RUNNING || status == DStatus.QUEUED
}

/** What the browser / share sheet / clipboard hands to the download dialog. */
data class DownloadRequest(
    val url: String,
    val fileName: String,
    val mime: String? = null,
    val size: Long = -1,
    val headers: Map<String, String> = emptyMap(),
)
