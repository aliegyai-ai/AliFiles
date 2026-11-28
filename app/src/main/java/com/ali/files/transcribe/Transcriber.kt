package com.ali.files.transcribe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ali.files.R
import com.ali.files.library.AskParser
import com.ali.files.library.LibFile
import com.ali.files.ui.UiState
import com.ali.files.util.AppSettings
import com.ali.files.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class WhisperSize(val key: String, val label: String, val note: String, val files: List<Pair<String, String>>, val mb: Int) {
    BASE(
        "base", "سريع", "حوالي 136 ميجا — أسرع، ودقته متوسطة في العربي",
        listOf(
            "encoder_v2.onnx" to "https://huggingface.co/onnx-community/whisper-base/resolve/main/onnx/encoder_model.onnx",
            "decoder.onnx" to "https://huggingface.co/onnx-community/whisper-base/resolve/main/onnx/decoder_model_merged_int8.onnx",
            "vocab.json" to "https://huggingface.co/onnx-community/whisper-base/resolve/main/vocab.json",
        ), 136,
    ),
    SMALL(
        "small", "أدق", "حوالي 225 ميجا — أدق بكتير في العربي، بس أبطأ",
        listOf(
            "encoder_v2.onnx" to "https://huggingface.co/onnx-community/whisper-small/resolve/main/onnx/encoder_model_q4.onnx",
            "decoder.onnx" to "https://huggingface.co/onnx-community/whisper-small/resolve/main/onnx/decoder_model_merged_int8.onnx",
            "vocab.json" to "https://huggingface.co/onnx-community/whisper-small/resolve/main/vocab.json",
        ), 250,
    ),
}

data class TrJob(
    val id: Long, val path: String, val title: String,
    val state: String = "في الانتظار…", val progress: Float = -1f,
    val done: Boolean = false, val error: String? = null,
)

@Serializable
data class Transcript(val path: String, val title: String, val segs: List<SegJson>)
@Serializable
data class SegJson(val s: Long, val e: Long, val t: String)

/** What was said in a file: kept in the app (not in the library), as text with times + an .srt for the player. */
object Transcripts {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var app: Context
    private val cache = HashMap<String, String>()
    /** bumps when something new was written, so screens can refresh */
    val version = MutableStateFlow(0)

    fun init(c: Context) { app = c.applicationContext }
    private fun dir() = File(app.filesDir, "transcripts").apply { mkdirs() }
    private fun key(path: String) = MessageDigest.getInstance("MD5").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
    fun srtFile(path: String): File? = if (!::app.isInitialized) null else File(dir(), key(path) + ".srt").takeIf { it.exists() }
    fun has(path: String) = ::app.isInitialized && File(dir(), key(path) + ".json").exists()

    fun save(path: String, title: String, segs: List<Seg>) {
        File(dir(), key(path) + ".json").writeText(json.encodeToString(Transcript(path, title, segs.map { SegJson(it.startMs, it.endMs, it.text) })))
        File(dir(), key(path) + ".srt").writeText(buildString {
            segs.forEachIndexed { i, s -> append(i + 1).append('\n').append(ts(s.startMs)).append(" --> ").append(ts(s.endMs)).append('\n').append(s.text).append("\n\n") }
        })
        cache.remove(path)
        version.value++
    }

    private fun ts(ms: Long): String = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    fun load(path: String): Transcript? = runCatching { json.decodeFromString<Transcript>(File(dir(), key(path) + ".json").readText()) }.getOrNull()

    fun delete(path: String) { File(dir(), key(path) + ".json").delete(); File(dir(), key(path) + ".srt").delete(); cache.remove(path); version.value++ }

    /** the spoken words, normalised, for searching */
    fun words(path: String): String {
        if (!::app.isInitialized) return ""
        return cache.getOrPut(path) { load(path)?.segs?.joinToString(" ") { it.t }?.let { AskParser.norm(it) } ?: "" }
    }

    data class Hit(val ms: Long, val text: String)

    /** where in the file the words are said (first few places) */
    fun where(path: String, words: List<String>): List<Hit> {
        val t = load(path) ?: return emptyList()
        return t.segs.filter { s -> AskParser.norm(s.t).let { n -> words.any { AskParser.has(n, it) } } }.take(5).map { Hit(it.s, it.t) }
    }
}

/** App side of «write what's said»: the list of jobs + the AI model files. */
object Transcriber {
    private val _jobs = MutableStateFlow<List<TrJob>>(emptyList())
    val jobs: StateFlow<List<TrJob>> = _jobs
    @Volatile var cancelAll = false
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun modelDir(c: Context, s: WhisperSize) = File(c.filesDir, "whisper/${s.key}")
    fun modelReady(c: Context, s: WhisperSize) = s.files.all { File(modelDir(c, s), it.first).let { f -> f.exists() && f.length() > 100_000 } }

    internal fun update(id: Long, f: (TrJob) -> TrJob) { _jobs.value = _jobs.value.map { if (it.id == id) f(it) else it } }
    internal fun pending(): TrJob? = _jobs.value.firstOrNull { !it.done }
    internal fun isCancelled(id: Long) = id in cancelled

    fun start(c: Context, f: LibFile) {
        if (_jobs.value.any { it.path == f.path && !it.done }) return
        _jobs.value = _jobs.value.filterNot { it.path == f.path } + TrJob(System.currentTimeMillis(), f.path, f.title)
        ContextCompat.startForegroundService(c, Intent(c, TranscribeService::class.java))
    }

    fun cancel(id: Long) { cancelled.add(id); update(id) { it.copy(done = true, error = "اتلغى") } }
    fun dismiss(id: Long) { _jobs.value = _jobs.value.filterNot { it.id == id } }
}

class TranscribeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private val awake by lazy { com.ali.files.util.KeepAwake(this, "transcribe") }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "كتابة الكلام", NotificationManager.IMPORTANCE_LOW))
        try {
            val n = note("بيبدأ…", -1f)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTE_ID, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (worker?.isActive != true) worker = scope.launch {
            awake.on()
            try {
                while (true) {
                    val job = Transcriber.pending() ?: break
                    runCatching { process(job) }.onFailure { e ->
                        if (!Transcriber.isCancelled(job.id)) Transcriber.update(job.id) { it.copy(done = true, error = e.message ?: "حصل خطأ") }
                    }
                }
            } finally { awake.off() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun progress(job: TrJob, state: String, p: Float) {
        Transcriber.update(job.id) { it.copy(state = state, progress = p) }
        nm.notify(NOTE_ID, note("${job.title} — $state", p))
    }

    private suspend fun process(job: TrJob) {
        val src = File(job.path)
        if (!src.exists()) throw IOException("الملف الأصلي اتمسح")
        val size = WhisperSize.entries.firstOrNull { it.key == AppSettings.whisperModel.value } ?: WhisperSize.BASE
        withContext(Dispatchers.IO) { ensureModel(job, size) }
        if (Transcriber.isCancelled(job.id)) return

        val work = File(cacheDir, "tr_${job.id}").apply { mkdirs() }
        try {
            progress(job, "بيجهّز الصوت…", -1f)
            val raw = File(work, "a.raw")
            withContext(Dispatchers.IO) {
                com.yausername.youtubedl_android.YoutubeDL.getInstance().init(applicationContext)
                com.yausername.ffmpeg.FFmpeg.getInstance().init(applicationContext)
                ffmpeg(listOf("-y", "-i", job.path, "-vn", "-ac", "1", "-ar", "16000", "-f", "f32le", raw.path))
            }
            if (Transcriber.isCancelled(job.id)) return
            progress(job, "بيحمّل النموذج…", -1f)
            val started = System.currentTimeMillis()
            val segs = withContext(Dispatchers.Default) {
                Whisper(Transcriber.modelDir(this@TranscribeService, size)).use { w ->
                    w.transcribe(raw, cancelled = { Transcriber.isCancelled(job.id) }) { p ->
                        val el = (System.currentTimeMillis() - started) / 1000
                        val eta = if (p > 0.03f) " • باقي ${formatEta((el / p * (1 - p)).toLong())}" else ""
                        progress(job, "بيكتب الكلام ${(p * 100).toInt()}%$eta", p)
                    }
                }
            }
            if (Transcriber.isCancelled(job.id)) return
            if (segs.isEmpty()) throw IOException("ما لقيتش كلام واضح في الملف")
            Transcripts.save(job.path, job.title, segs)
            Transcriber.update(job.id) { it.copy(done = true, state = "خلص ✅", progress = 1f) }
            nm.notify(job.id.toInt(), NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("اتكتب الكلام: ${job.title}").setContentText("دوّر عليه في «اسأل مكتبتك» أو شغّل الفيديو بالترجمة")
                .setContentIntent(Notifications.openAppIntent(this, UiState.TAB_DOWNLOADS)).setAutoCancel(true).build())
        } finally { work.deleteRecursively() }
    }

    private fun formatEta(s: Long) = if (s >= 3600) "${s / 3600} س ${s / 60 % 60} د" else "${(s / 60).coerceAtLeast(1)} د"

    private fun ffmpeg(args: List<String>) {
        val bin = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pkgs = File(noBackupFilesDir, "youtubedl-android/packages")
        val pb = ProcessBuilder(listOf(bin.path, "-nostdin", "-hide_banner", "-loglevel", "error") + args)
        pb.environment()["LD_LIBRARY_PATH"] = "${pkgs.path}/python/usr/lib:${pkgs.path}/ffmpeg/usr/lib"
        pb.redirectErrorStream(true)
        val p = pb.start()
        val log = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw IOException("تعذر قراءة الصوت من الملف (${log.lines().lastOrNull { it.isNotBlank() }?.take(120) ?: ""})")
    }

    /** downloads the model files the first time (resumes nothing: small files, one go) */
    private fun ensureModel(job: TrJob, size: WhisperSize) {
        if (Transcriber.modelReady(this, size)) return
        val dir = Transcriber.modelDir(this, size).apply { mkdirs() }
        File(dir, "encoder.onnx").delete()   // the first version didn't run on phones
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        size.files.forEachIndexed { idx, (name, url) ->
            val f = File(dir, name)
            if (f.exists() && f.length() > 100_000) return@forEachIndexed
            val tmp = File(dir, "$name.part")
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("تعذر تحميل النموذج (HTTP ${r.code})")
                val body = r.body ?: throw IOException("ملف فاضي")
                val total = body.contentLength()
                var done = 0L; var shown = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (Transcriber.isCancelled(job.id)) throw IOException("اتلغى")
                            if (total > 0 && done - shown > 2_000_000) {
                                shown = done
                                progress(job, "بينزّل نموذج الكتابة (${idx + 1}/${size.files.size}) ${(done * 100 / total)}%", done.toFloat() / total)
                            }
                        }
                    }
                }
            }
            if (!tmp.renameTo(f)) throw IOException("تعذر حفظ النموذج")
        }
    }

    private fun note(text: String, p: Float) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat).setContentTitle("كتابة الكلام").setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
        .setProgress(100, (p.coerceAtLeast(0f) * 100).toInt(), p < 0)
        .setContentIntent(Notifications.openAppIntent(this, UiState.TAB_DOWNLOADS)).build()

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    companion object { private const val CHANNEL = "transcribe"; private const val NOTE_ID = 7073 }
}
