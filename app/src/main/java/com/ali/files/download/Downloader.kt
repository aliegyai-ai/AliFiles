package com.ali.files.download

import android.content.Context
import android.net.Uri
import com.ali.files.util.FileStore
import com.ali.files.util.Notifications
import com.ali.files.util.formatBytes
import com.ali.files.util.mimeOf
import com.ali.files.util.sanitizeFileName
import com.ali.files.vault.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Download engine:
 *  - splits a file into N byte ranges and downloads them in parallel (when the server allows ranges)
 *  - pause / resume, also after the app is killed (state is saved to downloads.json)
 *  - HLS (.m3u8) streams, including AES-128 encrypted ones and fMP4 init segments
 *  - automatic retries per range
 *  - result goes to Download/AliFiles or straight into the encrypted vault
 */
object Downloader {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val MAX_CONCURRENT = 3

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val stateFile get() = File(app.filesDir, "downloads.json")
    private fun partFile(id: Long) = File(app.getExternalFilesDir(null) ?: app.filesDir, "partial/$id.part")
    private fun videoDir(id: Long) = File(app.getExternalFilesDir(null) ?: app.filesDir, "partial/v$id")

    fun init(c: Context) {
        app = c.applicationContext
        runCatching {
            if (stateFile.exists()) {
                val list = json.decodeFromString<List<DownloadItem>>(stateFile.readText())
                _items.value = list.map { if (it.active) it.copy(status = DStatus.PAUSED, speed = 0) else it.copy(speed = 0) }
            }
        }
    }

    @Synchronized
    private fun save() {
        runCatching {
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(json.encodeToString(_items.value))
            if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
        }
    }

    fun get(id: Long): DownloadItem? = _items.value.firstOrNull { it.id == id }

    private fun update(id: Long, f: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    fun isHlsUrl(url: String, mime: String?): Boolean =
        url.substringBefore('?').endsWith(".m3u8", true) || (mime?.contains("mpegurl", true) == true)

    fun enqueue(req: DownloadRequest, threads: Int, toVault: Boolean): Long {
        val id = System.currentTimeMillis() * 10 + Random.nextInt(10)
        val hls = isHlsUrl(req.url, req.mime)
        var name = sanitizeFileName(req.fileName)
        if (hls && !name.endsWith(".ts", true)) name = name.substringBeforeLast('.') + ".ts"
        val item = DownloadItem(
            id = id, url = req.url, fileName = name, mime = req.mime, total = req.size,
            threads = threads.coerceIn(1, 16), toVault = toVault, headers = req.headers, hls = hls,
        )
        _items.update { listOf(item) + it }
        save()
        schedule()
        return id
    }

    fun enqueueVideo(url: String, title: String, height: Int?, headers: Map<String, String>, toVault: Boolean): Long {
        val id = System.currentTimeMillis() * 10 + Random.nextInt(10)
        val audio = height == null
        val item = DownloadItem(
            id = id, url = url, fileName = sanitizeFileName(title) + if (audio) ".mp3" else ".mp4",
            mime = if (audio) "audio/mpeg" else "video/mp4", toVault = toVault, headers = headers,
            ytdlp = true, ytFormat = VideoExtractor.formatFor(height), audioOnly = audio,
            note = if (audio) "صوت بس" else "فيديو ${height}p",
        )
        _items.update { listOf(item) + it }
        save()
        schedule()
        return id
    }

    fun pause(id: Long) {
        update(id) { if (it.active) it.copy(status = DStatus.PAUSED, speed = 0) else it }
        if (get(id)?.ytdlp == true) VideoExtractor.cancel(id)
        jobs.remove(id)?.cancel()
        save()
        schedule()
    }

    fun resume(id: Long) {
        update(id) { if (it.status == DStatus.PAUSED || it.status == DStatus.FAILED) it.copy(status = DStatus.QUEUED, error = null) else it }
        save()
        schedule()
    }

    fun pauseAll() = _items.value.filter { it.active }.forEach { pause(it.id) }
    fun resumeAll() = _items.value.filter { it.status == DStatus.PAUSED }.forEach { resume(it.id) }

    fun remove(id: Long, deleteFile: Boolean) {
        if (get(id)?.ytdlp == true) VideoExtractor.cancel(id)
        jobs.remove(id)?.cancel()
        val item = get(id)
        partFile(id).delete()
        videoDir(id).deleteRecursively()
        if (deleteFile && item?.savedUri != null) runCatching { app.contentResolver.delete(Uri.parse(item.savedUri), null, null) }
        if (deleteFile && item?.libraryPath != null) { java.io.File(item.libraryPath).delete(); com.ali.files.library.Library.refresh() }
        _items.update { l -> l.filterNot { it.id == id } }
        save()
        schedule()
    }

    fun clearFinished() {
        _items.update { l -> l.filterNot { it.status == DStatus.COMPLETED } }
        save()
    }

    /** Moves a finished download from Download/AliFiles into the encrypted vault. */
    suspend fun moveToVault(id: Long) {
        val item = get(id) ?: return
        item.libraryPath?.let { path ->
            val f = java.io.File(path)
            if (f.exists()) {
                Vault.importFile(f, item.fileName, item.mime ?: mimeOf(item.fileName))
                f.delete()
                com.ali.files.library.Library.refresh()
            }
            update(id) { it.copy(libraryPath = null, savedInVault = true) }
            save()
            return
        }
        val uri = item.savedUri?.let { Uri.parse(it) } ?: return
        Vault.importUri(uri, item.fileName)
        runCatching { app.contentResolver.delete(uri, null, null) }
        update(id) { it.copy(savedUri = null, savedInVault = true) }
        save()
    }

    @Synchronized
    private fun schedule() {
        var slots = MAX_CONCURRENT - _items.value.count { it.status == DStatus.RUNNING }
        for (item in _items.value.reversed()) { // oldest first
            if (slots <= 0) break
            if (item.status == DStatus.QUEUED && !jobs.containsKey(item.id)) {
                start(item.id)
                slots--
            }
        }
        if (_items.value.any { it.active }) DownloadService.ensureRunning(app)
    }

    private fun start(id: Long) {
        update(id) { it.copy(status = DStatus.RUNNING, error = null) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                run(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                update(id) {
                    if (it.status == DStatus.PAUSED) it
                    else it.copy(status = DStatus.FAILED, error = e.message ?: e.javaClass.simpleName, speed = 0)
                }
                save()
            } finally {
                jobs.remove(id)
                schedule()
            }
        }
        jobs[id] = job
        job.start()
    }

    private suspend fun run(id: Long) {
        val item = get(id) ?: return
        partFile(id).parentFile?.mkdirs()
        if (item.ytdlp) {
            val out = runVideo(id)
            finish(id, out)
            videoDir(id).deleteRecursively()
            return
        }
        if (!item.hls && item.segments.isEmpty()) probe(id)
        if (get(id)?.hls == true) runHls(id) else runHttp(id)
        finish(id)
    }

    // ---------------------------------------------------------------- HTTP

    private fun request(item: DownloadItem, url: String = item.url, from: Long? = null, to: Long? = null): Request {
        val b = Request.Builder().url(url)
        item.headers.forEach { (k, v) -> if (v.isNotBlank()) b.header(k, v) }
        if (from != null) b.header("Range", if (to != null && to >= 0) "bytes=$from-$to" else "bytes=$from-")
        b.header("Accept-Encoding", "identity")
        return b.build()
    }

    private fun probe(id: Long) {
        val item = get(id) ?: return
        client.newCall(request(item, from = 0, to = 0)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("السيرفر رد بخطأ ${r.code}")
            val contentRange = r.header("Content-Range")
            var total = -1L
            var resumable = false
            if (r.code == 206 && contentRange != null) {
                total = contentRange.substringAfter('/').trim().toLongOrNull() ?: -1L
                resumable = total > 0
            } else {
                total = r.body?.contentLength() ?: -1L
            }
            val ct = r.body?.contentType()
            val mime = if (ct != null) "${ct.type}/${ct.subtype}" else item.mime
            if (isHlsUrl(item.url, mime)) {
                update(id) { it.copy(hls = true, mime = mime, fileName = it.fileName.substringBeforeLast('.') + ".ts") }
                return
            }
            val n = if (resumable && total > (2L shl 20)) item.threads else 1
            val segments = if (resumable) split(total, n) else listOf(Segment(0, -1, 0))
            update(id) { it.copy(total = total, resumable = resumable, segments = segments, mime = mime, downloaded = 0) }
        }
        RandomAccessFile(partFile(id), "rw").use { it.setLength(0) }
    }

    private fun split(total: Long, n: Int): List<Segment> {
        val size = total / n
        return (0 until n).map { i ->
            val start = i * size
            val end = if (i == n - 1) total - 1 else (i + 1) * size - 1
            Segment(start, end, 0)
        }
    }

    private suspend fun runHttp(id: Long) = coroutineScope {
        var item = get(id) ?: return@coroutineScope
        val part = partFile(id)
        if (!item.resumable || !part.exists()) {
            // cannot continue a non-resumable transfer: start over
            if (!item.resumable) {
                update(id) { it.copy(segments = listOf(Segment(0, -1, 0)), downloaded = 0) }
            } else {
                update(id) { it.copy(segments = it.segments.map { s -> s.copy(done = 0) }, downloaded = 0) }
            }
            RandomAccessFile(part, "rw").use { it.setLength(0) }
            item = get(id) ?: return@coroutineScope
        }
        val counters = item.segments.map { AtomicLong(it.done) }
        fun snapshot(speed: Long) = update(id) {
            it.copy(
                downloaded = counters.sumOf { c -> c.get() },
                speed = speed,
                segments = it.segments.mapIndexed { i, s -> s.copy(done = counters[i].get()) },
            )
        }
        val ticker = launch {
            var last = counters.sumOf { it.get() }
            var lastT = System.nanoTime()
            var smooth = 0.0
            while (isActive) {
                delay(700)
                val now = counters.sumOf { it.get() }
                val t = System.nanoTime()
                val inst = (now - last) * 1e9 / (t - lastT).coerceAtLeast(1)
                smooth = if (smooth == 0.0) inst else smooth * 0.7 + inst * 0.3
                last = now; lastT = t
                snapshot(smooth.toLong())
                save()
            }
        }
        try {
            item.segments.mapIndexed { i, seg -> async(Dispatchers.IO) { downloadSegment(item, seg, counters[i], part) } }.awaitAll()
        } finally {
            ticker.cancel()
            snapshot(0)
            save()
        }
        val done = get(id)
        if (done != null && done.total <= 0) update(id) { it.copy(total = it.downloaded) }
    }

    private suspend fun downloadSegment(item: DownloadItem, seg: Segment, counter: AtomicLong, part: File) = withContext(Dispatchers.IO) {
        val segLen = if (seg.end >= 0) seg.end - seg.start + 1 else -1L
        if (segLen >= 0 && counter.get() >= segLen) return@withContext
        var attempts = 0
        while (true) {
            try {
                val from = seg.start + counter.get()
                val req = if (item.resumable) request(item, from = from, to = seg.end) else request(item)
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("السيرفر رد بخطأ ${r.code}")
                    if (item.resumable && r.code != 206) throw IOException("السيرفر رفض التحميل المقسم")
                    val body = r.body ?: throw IOException("رد فاضي من السيرفر")
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(from)
                        val buf = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                var toWrite = n
                                if (segLen >= 0) {
                                    val remaining = segLen - counter.get()
                                    if (remaining <= 0) break
                                    if (toWrite > remaining) toWrite = remaining.toInt()
                                }
                                raf.write(buf, 0, toWrite)
                                counter.addAndGet(toWrite.toLong())
                            }
                        }
                    }
                }
                if (segLen >= 0 && counter.get() < segLen) throw IOException("الاتصال اتقطع قبل ما الجزء يكمل")
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                attempts++
                if (attempts >= 6) throw e
                if (!item.resumable) counter.set(0)
                delay(1500L * attempts)
            }
        }
    }

    // ---------------------------------------------------------------- HLS

    private class HlsSeg(val url: String, val keyUrl: String?, val iv: ByteArray?, val seq: Long)
    private class HlsPlaylist(val segments: List<HlsSeg>, val initUrl: String?)

    private fun resolve(base: String, rel: String): String =
        base.toHttpUrlOrNull()?.resolve(rel)?.toString() ?: rel

    private fun attr(line: String, name: String): String? =
        Regex("(?:^|[,:])$name=(\"[^\"]*\"|[^,]*)").find(line)?.groupValues?.get(1)?.trim()?.trim('"')

    private fun fetchBytes(item: DownloadItem, url: String): ByteArray {
        var attempts = 0
        while (true) {
            try {
                client.newCall(request(item, url)).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("السيرفر رد بخطأ ${r.code}")
                    return r.body?.bytes() ?: throw IOException("رد فاضي")
                }
            } catch (e: IOException) {
                attempts++
                if (attempts >= 5) throw e
                Thread.sleep(1000L * attempts)
            }
        }
    }

    private fun loadPlaylist(item: DownloadItem): HlsPlaylist {
        var url = item.url
        var text = String(fetchBytes(item, url))
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("الرابط مش قائمة HLS صالحة")
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            var best: String? = null
            var bestBw = -1L
            for (i in lines.indices) {
                val l = lines[i].trim()
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }?.trim()
                    if (next != null && bw > bestBw) { bestBw = bw; best = next }
                }
            }
            url = resolve(url, best ?: throw IOException("قائمة HLS فاضية"))
            text = String(fetchBytes(item, url))
        }
        val segs = ArrayList<HlsSeg>()
        var keyUrl: String? = null
        var iv: ByteArray? = null
        var seq = 0L
        var initUrl: String? = null
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-KEY:") -> {
                    val method = attr(l, "METHOD")
                    when (method) {
                        null, "NONE" -> { keyUrl = null; iv = null }
                        "AES-128" -> {
                            keyUrl = attr(l, "URI")?.let { resolve(url, it) }
                            iv = attr(l, "IV")?.let { hexToBytes(it) }
                        }
                        else -> throw IOException("الفيديو محمي بتشفير $method (DRM) ومينفعش يتحمل")
                    }
                }
                l.startsWith("#EXT-X-MAP:") -> initUrl = attr(l, "URI")?.let { resolve(url, it) }
                l.isNotEmpty() && !l.startsWith("#") -> { segs.add(HlsSeg(resolve(url, l), keyUrl, iv, seq)); seq++ }
            }
        }
        if (segs.isEmpty()) throw IOException("مفيش أجزاء في القائمة")
        return HlsPlaylist(segs, initUrl)
    }

    private fun hexToBytes(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun seqIv(seq: Long): ByteArray {
        val b = ByteArray(16)
        for (i in 0 until 8) b[15 - i] = (seq ushr (8 * i)).toByte()
        return b
    }

    private fun decrypt(item: DownloadItem, seg: HlsSeg, raw: ByteArray, keys: ConcurrentHashMap<String, ByteArray>): ByteArray {
        val keyUrl = seg.keyUrl ?: return raw
        val key = keys[keyUrl] ?: fetchBytes(item, keyUrl).also { keys[keyUrl] = it }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(seg.iv ?: seqIv(seg.seq)))
        return c.doFinal(raw)
    }

    private suspend fun runHls(id: Long) = coroutineScope {
        val first = get(id) ?: return@coroutineScope
        val playlist = withContext(Dispatchers.IO) { loadPlaylist(first) }
        update(id) { it.copy(hlsTotal = playlist.segments.size) }
        val item = get(id) ?: return@coroutineScope
        val part = partFile(id)
        var index = item.hlsIndex
        var written = item.downloaded
        val keys = ConcurrentHashMap<String, ByteArray>()
        val startT = System.nanoTime()
        var sessionBytes = 0L
        withContext(Dispatchers.IO) {
            RandomAccessFile(part, "rw").use { raf ->
                if (index == 0 || !part.exists()) {
                    index = 0; written = 0
                    raf.setLength(0)
                    playlist.initUrl?.let { val b = fetchBytes(item, it); raf.write(b); written += b.size }
                } else {
                    raf.setLength(written)
                }
                raf.seek(written)
                val batchSize = item.threads.coerceIn(1, 8)
                while (index < playlist.segments.size) {
                    ensureActive()
                    val batch = playlist.segments.subList(index, minOf(index + batchSize, playlist.segments.size))
                    val data = batch.map { s -> async(Dispatchers.IO) { decrypt(item, s, fetchBytes(item, s.url), keys) } }.awaitAll()
                    for (d in data) { raf.write(d); written += d.size; sessionBytes += d.size }
                    index += batch.size
                    val speed = (sessionBytes * 1e9 / (System.nanoTime() - startT).coerceAtLeast(1)).toLong()
                    val i = index; val w = written
                    update(id) { it.copy(hlsIndex = i, downloaded = w, speed = speed) }
                    save()
                }
            }
        }
        update(id) { it.copy(total = it.downloaded, speed = 0) }
    }

    // ---------------------------------------------------------------- yt-dlp

    private suspend fun runVideo(id: Long): File = withContext(Dispatchers.IO) {
        val item = get(id) ?: throw IOException("التحميل اتشال")
        val dir = videoDir(id).apply { mkdirs() }
        var lastSave = 0L
        val out = VideoExtractor.download(item, dir) { percent, total, speed, note ->
            update(id) {
                val t = if (total > 0) total else it.total
                it.copy(
                    total = t,
                    downloaded = if (t > 0) (t * percent / 100f).toLong() else it.downloaded,
                    speed = speed,
                    note = note ?: (if (it.audioOnly) "صوت بس" else "فيديو"),
                )
            }
            val now = System.currentTimeMillis()
            if (now - lastSave > 2000) { lastSave = now; save() }
        }
        val name = sanitizeFileName(out.name)
        update(id) { it.copy(fileName = name, mime = mimeOf(name), total = out.length(), downloaded = out.length(), note = null) }
        out
    }

    // ---------------------------------------------------------------- finish

    private suspend fun finish(id: Long, src: File? = null) = withContext(Dispatchers.IO) {
        val item = get(id) ?: return@withContext
        val part = src ?: partFile(id)
        val mime = when {
            item.hls -> "video/mp2t"
            item.mime.isNullOrBlank() || item.mime == "application/octet-stream" || item.mime.startsWith("text/plain") -> mimeOf(item.fileName)
            else -> item.mime ?: mimeOf(item.fileName)
        }
        if (item.toVault) {
            Vault.importFile(part, item.fileName, mime)
            part.delete()
            update(id) { it.copy(status = DStatus.COMPLETED, speed = 0, savedInVault = true, mime = mime) }
            save()
            Notifications.notifyDone(app, id, "اتحمل في الخزنة 🔒", item.fileName)
        } else {
            // stays inside the app (the library); the user saves it to the phone only if they want
            val dest = com.ali.files.library.Library.add(part, item.fileName)
            update(id) { it.copy(status = DStatus.COMPLETED, speed = 0, libraryPath = dest.absolutePath, fileName = dest.name, mime = mime) }
            save()
            Notifications.notifyDone(app, id, "اتحمّل في المكتبة", "${dest.name} • ${formatBytes(get(id)?.downloaded ?: 0)}")
        }
    }
}
