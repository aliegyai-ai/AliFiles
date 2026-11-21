package com.ali.files.download

import kotlinx.serialization.Serializable

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
    val toVault: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    val savedUri: String? = null,
    val savedInVault: Boolean = false,
    val resumable: Boolean = false,
    val segments: List<Segment> = emptyList(),
    val hls: Boolean = false,
    val hlsIndex: Int = 0,
    val hlsTotal: Int = 0,
    val speed: Long = 0,
    val ytdlp: Boolean = false,
    val ytFormat: String? = null,
    val audioOnly: Boolean = false,
    val note: String? = null,
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
