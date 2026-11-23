package com.ali.files.util

import android.content.Context
import com.ali.files.separate.Separator
import java.io.File

/** What the app keeps on the phone, how big each part is, and cleaning the parts that are safe to clean. */
object StorageInfo {
    enum class Kind { LIBRARY, VAULT, ENGINE, MODEL, CACHE, WEB_CACHE, PARTIAL, SEPARATE }

    data class Item(val kind: Kind, val title: String, val sub: String, val bytes: Long, val clearable: Boolean)

    /** cache folders the app can always throw away (they're rebuilt when needed) */
    private fun cacheDirs(c: Context) = listOf("ytdlp-cache", "update", "vault_open", "image_cache", "tmp")
        .map { File(c.cacheDir, it) } +
        (c.cacheDir.listFiles()?.filter { it.isFile } ?: emptyList())

    private fun webCache(c: Context) = File(c.cacheDir, "WebView")

    fun size(f: File): Long = when {
        !f.exists() -> 0
        f.isFile -> f.length()
        else -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun separating() = Separator.jobs.value.any { !it.done && it.error == null }

    fun items(c: Context): List<Item> {
        val ext = c.getExternalFilesDir(null) ?: c.filesDir
        // everything in the cache folder except the browser's and the library's pictures (counted on their own)
        val cache = c.cacheDir.listFiles()?.filter { it.name !in setOf("WebView", "libthumbs") }?.sumOf { size(it) } ?: 0
        return listOf(
            Item(Kind.LIBRARY, "المكتبة", "الفيديوهات والأغاني اللي حمّلتها — بتتمسح من المكتبة نفسها", size(File(c.filesDir, "library")) + size(File(c.cacheDir, "libthumbs")), false),
            Item(Kind.VAULT, "الخزنة", "ملفاتك المشفّرة — بتتمسح من جوه الخزنة", size(File(c.filesDir, "vault")), false),
            Item(Kind.MODEL, "موديل عزل الصوت", "بيتنزل تاني لوحده لو احتجته", size(File(c.filesDir, "models")), !separating()),
            Item(Kind.SEPARATE, "ملفات عزل الصوت المؤقتة", "بقايا عمليات العزل", size(File(c.filesDir, "separate")), !separating()),
            Item(Kind.PARTIAL, "بقايا تحميلات", "ملفات من تحميلات خلصت أو اتمسحت", size(File(ext, "partial")), true),
            Item(Kind.WEB_CACHE, "كاش المتصفح", "صور وملفات المواقع — بتتنزل تاني لما تفتحها", size(webCache(c)), true),
            Item(Kind.CACHE, "الكاش المؤقت", "معلومات الفيديوهات والتحديثات القديمة", cache, true),
            Item(Kind.ENGINE, "محرك التحميل", "بايثون وFFmpeg — لازمين عشان التحميل يشتغل", size(File(c.noBackupFilesDir, "youtubedl-android")) + size(File(c.applicationInfo.nativeLibraryDir)), false),
        )
    }

    /** [clearWeb] clears the browser's cache (must run on the main thread with a WebView around). */
    fun clear(c: Context, kind: Kind, clearWeb: () -> Unit) {
        when (kind) {
            Kind.MODEL -> File(c.filesDir, "models").deleteRecursively()
            Kind.SEPARATE -> File(c.filesDir, "separate").listFiles()?.forEach { it.deleteRecursively() }
            Kind.PARTIAL -> {
                // keep the parts of downloads that can still be resumed
                val busy = com.ali.files.download.Downloader.items.value
                    .filter { it.status != com.ali.files.download.DStatus.COMPLETED }.map { it.id }
                File(c.getExternalFilesDir(null) ?: c.filesDir, "partial").listFiles()
                    ?.filter { f -> busy.none { id -> f.name == "$id.part" || f.name == "v$id" } }
                    ?.forEach { it.deleteRecursively() }
            }
            Kind.WEB_CACHE -> clearWeb()
            Kind.CACHE -> {
                cacheDirs(c).forEach { it.deleteRecursively() }
                // not the browser's/library's pictures, and not a magnet link being read right now
                c.cacheDir.listFiles()?.filter { it.isDirectory && it.name !in setOf("WebView", "libthumbs", "magnet") }?.forEach { it.deleteRecursively() }
            }
            else -> {}
        }
    }

    /** Runs at start-up: keeps the throw-away caches under [LIMIT]. Returns true if the browser cache should be cleared too. */
    fun autoClean(c: Context): Boolean {
        runCatching {
            val cache = cacheDirs(c).sumOf { size(it) }
            if (cache > LIMIT) cacheDirs(c).forEach { it.deleteRecursively() }
        }
        return runCatching { size(webCache(c)) > LIMIT }.getOrDefault(false)
    }

    const val LIMIT = 200L * 1024 * 1024
}
