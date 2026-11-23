package com.ali.files.torrent

import android.content.Context
import android.net.ConnectivityManager
import com.ali.files.library.Library
import com.ali.files.util.AppSettings
import com.ali.files.util.mimeOf
import com.ali.files.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.AddTorrentParams
import java.io.File

/** One torrent we keep track of (saved to disk so it continues after the app restarts). */
@Serializable
data class TorrentRec(
    val hash: String,
    val name: String,
    /** indexes of the files the user picked */
    val selected: List<Int>,
    val sequential: Boolean,
    val toVault: Boolean,
    val paused: Boolean = false,
    val done: Boolean = false,
    /** where the finished files went (for the list) */
    val result: String? = null,
    val wanted: Long = 0,
    val completedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
)

/** What the list shows, refreshed every second. */
data class TorrentItem(
    val rec: TorrentRec,
    val progress: Float = 0f,
    val down: Int = 0,
    val up: Int = 0,
    val peers: Int = 0,
    val seeds: Int = 0,
    val doneBytes: Long = 0,
    val state: String = "",
    val seeding: Boolean = false,
    /** selected files: (index, path on disk while downloading, progress 0..1) */
    val files: List<Triple<Int, String, Float>> = emptyList(),
)

/** A torrent being looked at before starting: its files. */
class PendingTorrent(val data: ByteArray, val info: TorrentInfo) {
    val name: String get() = info.name()
    val files: List<Pair<String, Long>> =
        (0 until info.numFiles()).map { info.files().filePath(it) to info.files().fileSize(it) }
}

/**
 * Torrent downloads, with libtorrent (the engine inside qBittorrent).
 * Magnet links and .torrent files, choosing files, in-order download (watch while it downloads),
 * pause / resume across restarts, Wi-Fi only, speed limit, and stopping uploads when done.
 * Finished files go to the library (or the vault).
 */
object TorrentEngine {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var session: SessionManager? = null
    private val lock = Any()

    private val recs = MutableStateFlow<List<TorrentRec>>(emptyList())
    private val _items = MutableStateFlow<List<TorrentItem>>(emptyList())
    val items: StateFlow<List<TorrentItem>> = _items
    /** waiting for Wi-Fi (the "Wi-Fi only" setting) */
    val waitingWifi = MutableStateFlow(false)

    private val dir get() = File(app.filesDir, "torrents").apply { mkdirs() }
    private val indexFile get() = File(dir, "index.json")
    private fun metaFile(h: String) = File(dir, "$h.torrent")
    private fun resumeFile(h: String) = File(dir, "$h.resume")
    fun dataDir(h: String) = File(dir, h)

    val active: Boolean get() = recs.value.any { !it.done }
    /** magnet links being read right now (the engine must stay on for them) */
    private val fetching = java.util.concurrent.atomic.AtomicInteger(0)

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        runCatching { recs.value = json.decodeFromString<List<TorrentRec>>(indexFile.readText()) }
        publish()
        if (active) scope.launch { ensureSession(); recs.value.filter { !it.done }.forEach { add(it) } ; startLoop() }
    }

    private fun save() {
        runCatching {
            val tmp = File(dir, "index.tmp")
            tmp.writeText(json.encodeToString(recs.value))
            tmp.renameTo(indexFile)
        }
    }

    private fun ensureSession(): SessionManager = synchronized(lock) {
        session?.let { return it }
        val sp = SettingsPack()
            .activeDownloads(4)
            .activeSeeds(2)
            .connectionsLimit(200)
        val s = SessionManager(false)
        s.addListener(object : AlertListener {
            override fun types() = intArrayOf(AlertType.SAVE_RESUME_DATA.swig())
            override fun alert(a: Alert<*>) {
                if (a is SaveResumeDataAlert) runCatching {
                    val h = a.handle().infoHash().toHex()
                    resumeFile(h).writeBytes(AddTorrentParams.writeResumeDataBuf(a.params()))
                }
            }
        })
        s.start(SessionParams(sp))
        applyLimits(s)
        session = s
        s
    }

    fun applyLimits(s: SessionManager? = session) {
        s ?: return
        val kb = AppSettings.torrentLimitKb.value
        s.downloadRateLimit(if (kb > 0) kb * 1024 else 0)
        // a little upload keeps the other side sharing with us; none at all once done (unless allowed)
        s.uploadRateLimit(if (AppSettings.torrentSeed.value) 0 else 50 * 1024)
    }

    private fun handle(h: String): TorrentHandle? =
        session?.find(Sha1Hash.parseHex(h))?.takeIf { it.isValid }

    /** Reads a magnet link (asks the network for its file list) — can take up to a minute. */
    suspend fun fromMagnet(uri: String): PendingTorrent = withContext(Dispatchers.IO) {
        val s = ensureSession()
        startLoop()
        fetching.incrementAndGet()
        val data = try {
            s.fetchMagnet(uri, 90, File(app.cacheDir, "magnet").apply { mkdirs() })
        } finally { fetching.decrementAndGet() }
            ?: error("مقدرتش أجيب معلومات التورنت — اتأكد من اللينك والنت")
        PendingTorrent(data, TorrentInfo(data))
    }

    fun fromBytes(data: ByteArray): PendingTorrent = PendingTorrent(data, TorrentInfo(data))

    fun start(p: PendingTorrent, selected: Set<Int>, sequential: Boolean, toVault: Boolean) {
        val h = p.info.infoHash().toHex()
        if (recs.value.any { it.hash == h && !it.done }) return
        metaFile(h).writeBytes(p.data)
        val wanted = selected.sumOf { p.files[it].second }
        val rec = TorrentRec(h, p.name, selected.sorted(), sequential, toVault, wanted = wanted)
        recs.value = recs.value.filterNot { it.hash == h } + rec
        save()
        publish()   // in the list right away (the background service checks the list)
        scope.launch { ensureSession(); add(rec); startLoop() }
        TorrentService.ensureRunning(app)
    }

    private fun add(r: TorrentRec) {
        val s = ensureSession()
        val ti = runCatching { TorrentInfo(metaFile(r.hash)) }.getOrNull() ?: return
        val prio = Array(ti.numFiles()) { if (it in r.selected) Priority.DEFAULT else Priority.IGNORE }
        var flags = TorrentFlags.AUTO_MANAGED
        if (r.sequential) flags = flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val resume = resumeFile(r.hash).takeIf { it.exists() }
        s.download(ti, dataDir(r.hash).apply { mkdirs() }, resume, prio, null, flags)
        if (r.paused) scope.launch {
            repeat(20) { handle(r.hash)?.let { pauseHandle(it); return@launch }; delay(200) }
        }
    }

    private fun pauseHandle(t: TorrentHandle) {
        t.unsetFlags(TorrentFlags.AUTO_MANAGED)
        t.pause()
        t.saveResumeData()
    }

    private fun update(h: String, f: (TorrentRec) -> TorrentRec) {
        recs.value = recs.value.map { if (it.hash == h) f(it) else it }
        save()
    }

    fun pause(h: String) { update(h) { it.copy(paused = true) }; handle(h)?.let { pauseHandle(it) }; publish() }

    fun resume(h: String) {
        update(h) { it.copy(paused = false) }
        val t = handle(h)
        if (t != null) { t.setFlags(TorrentFlags.AUTO_MANAGED); t.resume() }
        else scope.launch { recs.value.firstOrNull { it.hash == h }?.let { add(it) } }
        TorrentService.ensureRunning(app)
        publish()
    }

    fun setSequential(h: String, on: Boolean) {
        update(h) { it.copy(sequential = on) }
        handle(h)?.let { if (on) it.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else it.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
    }

    /** Removes from the list; [deleteFiles] also deletes what was downloaded so far. */
    fun remove(h: String, deleteFiles: Boolean = true) {
        handle(h)?.let { t -> session?.remove(t) }
        recs.value = recs.value.filterNot { it.hash == h }
        save()
        metaFile(h).delete(); resumeFile(h).delete()
        if (deleteFiles) scope.launch { delay(1500); dataDir(h).deleteRecursively() }
        publish()
    }

    fun clearFinished() {
        recs.value = recs.value.filterNot { it.done }
        save(); publish()
    }

    // ---------------------------------------------------------------- the loop
    @Volatile private var looping = false

    private fun startLoop() {
        if (looping) return
        looping = true
        scope.launch {
            var tick = 0
            while (isActive) {
                runCatching { step(tick++) }
                delay(1000)
                if (!active && fetching.get() == 0 && session != null && tick > 5) {
                    // nothing left: switch the engine off to save battery
                    synchronized(lock) { runCatching { session?.stop() }; session = null }
                    break
                }
            }
            looping = false
        }
    }

    private suspend fun step(tick: Int) {
        val s = session ?: return
        // Wi-Fi only
        val metered = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
        val wait = AppSettings.torrentWifiOnly.value && metered
        if (wait != waitingWifi.value) {
            waitingWifi.value = wait
            if (wait) s.pause() else s.resume()
        }
        if (tick % 30 == 29) recs.value.filter { !it.done }.forEach { r -> handle(r.hash)?.takeIf { it.needSaveResumeData() }?.saveResumeData() }

        for (r in recs.value.filter { !it.done }) {
            val t = handle(r.hash) ?: continue
            val st = t.status()
            val complete = st.hasMetadata() && st.totalWanted() > 0 && st.totalWantedDone() >= st.totalWanted()
            if (complete && r.completedAt == 0L) update(r.hash) { it.copy(completedAt = System.currentTimeMillis()) }
            if (complete) {
                val rr = recs.value.first { it.hash == r.hash }
                val ratio = if (st.totalWanted() > 0) st.allTimeUpload().toDouble() / st.totalWanted() else 1.0
                val seedLongEnough = System.currentTimeMillis() - rr.completedAt > 60 * 60_000L
                if (!AppSettings.torrentSeed.value || ratio >= 1.0 || seedLongEnough) finish(rr, t)
            }
        }
        publish()
    }

    /** Done: stop sharing and move the chosen files into the library or the vault. */
    private suspend fun finish(r: TorrentRec, t: TorrentHandle) {
        val ti = t.torrentFile() ?: return
        session?.remove(t)
        delay(1000) // let libtorrent close the files
        val base = dataDir(r.hash)
        var moved = 0
        for (i in r.selected) {
            val f = File(base, ti.files().filePath(i))
            if (!f.exists()) continue
            val name = f.name
            runCatching {
                if (r.toVault) { Vault.importFile(f, name, mimeOf(name)); f.delete() }
                else Library.add(f, name)
                moved++
            }
        }
        base.deleteRecursively()
        metaFile(r.hash).delete(); resumeFile(r.hash).delete()
        update(r.hash) { it.copy(done = true, paused = false, result = if (r.toVault) "في الخزنة" else "في المكتبة") }
        com.ali.files.util.Notifications.notifyDone(app, r.hash.hashCode().toLong() + 900_000, r.name, if (r.toVault) "التورنت خلص واتحفظ في الخزنة" else "التورنت خلص — الملفات في المكتبة")
    }

    private fun stateText(st: TorrentStatus, r: TorrentRec): String = when {
        r.paused -> "متوقف"
        waitingWifi.value -> "مستني واي فاي"
        else -> when (st.state()) {
            TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> "بيراجع الملفات…"
            TorrentStatus.State.DOWNLOADING_METADATA -> "بيجيب المعلومات…"
            TorrentStatus.State.DOWNLOADING -> if (st.numPeers() == 0) "بيدوّر على ناس عندهم الملف…" else "بيحمّل"
            TorrentStatus.State.FINISHED, TorrentStatus.State.SEEDING -> "خلص — بيشارك شوية"
            else -> ""
        }
    }

    private fun publish() {
        _items.value = recs.value.sortedByDescending { it.addedAt }.map { r ->
            if (r.done) return@map TorrentItem(r, progress = 1f, state = r.result ?: "خلص")
            val t = handle(r.hash) ?: return@map TorrentItem(r, state = if (r.paused) "متوقف" else "بيبدأ…")
            runCatching {
                val st = t.status()
                val ti = t.torrentFile()
                val fp = if (ti != null) t.fileProgress() else LongArray(0)
                val files = if (ti == null) emptyList() else r.selected.map { i ->
                    val size = ti.files().fileSize(i).coerceAtLeast(1)
                    Triple(i, File(dataDir(r.hash), ti.files().filePath(i)).absolutePath, (fp.getOrElse(i) { 0 }.toFloat() / size).coerceIn(0f, 1f))
                }
                TorrentItem(
                    rec = r,
                    progress = if (st.totalWanted() > 0) st.totalWantedDone().toFloat() / st.totalWanted() else 0f,
                    down = if (r.paused) 0 else st.downloadPayloadRate(),
                    up = if (r.paused) 0 else st.uploadPayloadRate(),
                    peers = st.numPeers(), seeds = st.numSeeds(),
                    doneBytes = st.totalWantedDone(),
                    state = stateText(st, r),
                    seeding = st.isSeeding || st.isFinished,
                    files = files,
                )
            }.getOrElse { TorrentItem(r, state = "…") }
        }
    }
}
