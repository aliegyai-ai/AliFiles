package com.ali.files.separate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
import com.ali.files.library.LibFile
import com.ali.files.library.Library
import com.ali.files.ui.UiState
import com.ali.files.util.FileKind
import com.ali.files.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit

@Serializable
enum class SepMode(val label: String, val suffix: String) {
    /** keep the voice, remove the music */
    VOICE("بدون موسيقى", "بدون موسيقى"),
    /** keep the music, remove the voice */
    MUSIC("الموسيقى بس", "موسيقى بس"),
}

@Serializable
data class SepJob(
    val id: Long,
    val path: String,
    val title: String,
    val mode: SepMode,
    val isVideo: Boolean,
    val state: String = "في الانتظار…",
    val progress: Float = -1f,
    val etaSec: Long = -1,
    val done: Boolean = false,
    val error: String? = null,
    val output: String? = null,
    /** for resuming after Android stopped the work: parts already finished */
    val nextChunk: Int = 0,
    val decoded: Boolean = false,
)

/** The job list on disk — shared by the app and the separate worker process. */
internal object SepStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun dir(c: Context) = File(c.filesDir, "separate").apply { mkdirs() }
    private fun file(c: Context) = File(dir(c), "jobs.json")
    private fun beat(c: Context) = File(dir(c), "heartbeat")

    fun read(c: Context): List<SepJob> = runCatching { json.decodeFromString<List<SepJob>>(file(c).readText()) }.getOrDefault(emptyList())

    @Synchronized fun write(c: Context, l: List<SepJob>) {
        val tmp = File(dir(c), "jobs.json.tmp")
        tmp.writeText(json.encodeToString(l))
        tmp.renameTo(file(c))
    }

    fun heartbeat(c: Context) = runCatching { beat(c).writeText(System.currentTimeMillis().toString()) }
    fun lastBeat(c: Context): Long = runCatching { beat(c).readText().toLong() }.getOrDefault(0L)
    fun workDir(c: Context, id: Long) = File(dir(c), "job_$id")
}

/**
 * App side of «remove the music». The heavy AI work runs in [SeparateService], in its own process
 * (`:separate`) — the model needs over 1 GB of memory while it runs, and in its own process Android
 * doesn't kill it when you leave the app or open the browser. Progress is saved after every part,
 * so if Android still stops it, it continues from where it was.
 */
object Separator {
    private val _jobs = MutableStateFlow<List<SepJob>>(emptyList())
    val jobs: StateFlow<List<SepJob>> = _jobs
    private val hidden = HashSet<Long>()
    private val seenDone = HashSet<Long>()

    fun start(c: Context, f: LibFile, mode: SepMode) {
        val i = Intent(c, SeparateService::class.java).setAction(SeparateService.ADD)
            .putExtra("id", System.currentTimeMillis())
            .putExtra("path", f.path).putExtra("title", f.title)
            .putExtra("mode", mode.name).putExtra("video", f.kind == FileKind.VIDEO)
        ContextCompat.startForegroundService(c, i)
    }

    fun cancel(c: Context, id: Long) {
        ContextCompat.startForegroundService(c, Intent(c, SeparateService::class.java).setAction(SeparateService.CANCEL).putExtra("id", id))
    }

    fun dismiss(id: Long) {
        hidden.add(id)
        _jobs.value = _jobs.value.filterNot { it.id == id }
    }

    /** Called about once a second while the app is open: refreshes the cards, and restarts stopped work. */
    fun poll(c: Context) {
        val all = SepStore.read(c)
        _jobs.value = all.filterNot { it.id in hidden }
        if (all.any { it.done && it.output != null && seenDone.add(it.id) }) Library.refresh()
        val pending = all.any { !it.done }
        if (pending && System.currentTimeMillis() - SepStore.lastBeat(c) > 30_000) {
            // the worker isn't running (Android stopped it) → continue from the last finished part
            SepStore.heartbeat(c)
            runCatching { ContextCompat.startForegroundService(c, Intent(c, SeparateService::class.java).setAction(SeparateService.RESUME)) }
        }
    }
}

/** Runs the separation in its own process, with a progress notification. */
class SeparateService : Service() {
    private val awake by lazy { com.ali.files.util.KeepAwake(this, "separate") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val lock = Any()
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "فصل الموسيقى", NotificationManager.IMPORTANCE_LOW))
        try {
            val first = notification("بيبدأ…", -1f)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTE_ID, first)
        } catch (e: Exception) {
            // Android refused to start in the background — the app restarts it next time it's opened
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ADD -> {
                val job = SepJob(
                    id = intent.getLongExtra("id", System.currentTimeMillis()),
                    path = intent.getStringExtra("path") ?: "",
                    title = intent.getStringExtra("title") ?: "",
                    mode = runCatching { SepMode.valueOf(intent.getStringExtra("mode") ?: "") }.getOrDefault(SepMode.VOICE),
                    isVideo = intent.getBooleanExtra("video", false),
                )
                edit { it + job }
            }
            CANCEL -> {
                val id = intent.getLongExtra("id", -1)
                cancelled.add(id)
                edit { l -> l.map { if (it.id == id && !it.done) it.copy(done = true, error = "اتلغى") else it } }
            }
        }
        if (worker?.isActive != true) worker = scope.launch {
            // screen off / another app: keep the processor awake until the work is done
            awake.on()
            try { work() } finally { awake.off() }
        }
        return START_STICKY
    }

    private fun edit(f: (List<SepJob>) -> List<SepJob>) = synchronized(lock) { SepStore.write(this, f(SepStore.read(this))) }
    private fun set(id: Long, f: (SepJob) -> SepJob) = edit { l -> l.map { if (it.id == id) f(it) else it } }
    private fun get(id: Long) = SepStore.read(this).firstOrNull { it.id == id }

    private suspend fun work() {
        val beat = scope.launch { while (isActive) { SepStore.heartbeat(this@SeparateService); delay(5_000) } }
        while (true) {
            val job = SepStore.read(this).firstOrNull { !it.done } ?: break
            process(job)
            get(job.id)?.let { done ->
                if (done.output != null) nm.notify(job.id.toInt(), NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("خلص: ${done.title} (${done.mode.label})")
                    .setContentText("موجود في المكتبة")
                    .setContentIntent(Notifications.openAppIntent(this, UiState.TAB_DOWNLOADS))
                    .setAutoCancel(true)
                    .build())
            }
        }
        beat.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun progress(job: SepJob, state: String, p: Float, eta: Long = -1) {
        set(job.id) { it.copy(state = state, progress = p, etaSec = eta) }
        nm.notify(NOTE_ID, notification("${job.title} — $state", p))
    }

    private class CancelledByUser : Exception()

    private fun check(job: SepJob) { if (job.id in cancelled) throw CancelledByUser() }

    private suspend fun process(start: SepJob) {
        val work = SepStore.workDir(this, start.id).apply { mkdirs() }
        var job = start
        try {
            if (!File(job.path).exists()) throw IOException("الملف الأصلي اتمسح")
            val model = ensureModel(job)
            check(job)
            withContext(Dispatchers.IO) {
                // the bundled ffmpeg + its libraries (unpacked by the app; quick if already there)
                com.yausername.youtubedl_android.YoutubeDL.getInstance().init(applicationContext)
                com.yausername.ffmpeg.FFmpeg.getInstance().init(applicationContext)
            }
            val raw = File(work, "in.raw")
            if (!job.decoded || !raw.exists()) {
                progress(job, "بيجهّز الصوت…", -1f)
                File(work, "out.raw").delete()
                ffmpeg(listOf("-y", "-i", job.path, "-vn", "-ac", "2", "-ar", "44100", "-f", "f32le", raw.path), "تعذر قراءة الصوت من الملف")
                set(job.id) { it.copy(decoded = true, nextChunk = 0) }
                job = get(job.id) ?: return
            }
            check(job)

            val outRaw = File(work, "out.raw")
            separate(model, raw, outRaw, job)
            check(job)

            progress(job, "بيحفظ الملف…", -1f)
            val src = File(job.path)
            val dest: File = if (job.isVideo) {
                val ext = if (src.extension.lowercase() == "mp4") "mp4" else "mkv"
                val out = Library.uniqueFile("${job.title} (${job.mode.suffix}).$ext", java.io.File(Library.dir, job.mode.suffix).apply { mkdirs() })
                ffmpeg(listOf(
                    "-y", "-i", job.path, "-f", "f32le", "-ar", "44100", "-ac", "2", "-i", outRaw.path,
                    "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", out.path,
                ), "تعذر حفظ الفيديو")
                out
            } else {
                val mp3 = Library.uniqueFile("${job.title} (${job.mode.suffix}).mp3", java.io.File(Library.dir, job.mode.suffix).apply { mkdirs() })
                try {
                    ffmpeg(listOf("-y", "-f", "f32le", "-ar", "44100", "-ac", "2", "-i", outRaw.path, "-c:a", "libmp3lame", "-q:a", "2", mp3.path), "تعذر حفظ الصوت")
                    mp3
                } catch (e: IOException) {
                    mp3.delete()
                    val m4a = Library.uniqueFile("${job.title} (${job.mode.suffix}).m4a", java.io.File(Library.dir, job.mode.suffix).apply { mkdirs() })
                    ffmpeg(listOf("-y", "-f", "f32le", "-ar", "44100", "-ac", "2", "-i", outRaw.path, "-c:a", "aac", "-b:a", "256k", m4a.path), "تعذر حفظ الصوت")
                    m4a
                }
            }
            set(job.id) { it.copy(done = true, state = "خلص ✅", progress = 1f, etaSec = -1, output = dest.absolutePath) }
            work.deleteRecursively()
        } catch (e: CancelledByUser) {
            work.deleteRecursively()
        } catch (e: Throwable) {
            set(job.id) { it.copy(done = true, error = e.message ?: "حصل خطأ") }
            work.deleteRecursively()
        } finally {
            cancelled.remove(job.id)
        }
    }

    /** Downloads the AI model the first time (about 67 MB). */
    private fun ensureModel(job: SepJob): File {
        val dir = File(filesDir, "models").apply { mkdirs() }
        val f = File(dir, MODEL_NAME)
        if (f.exists() && f.length() > MODEL_MIN_SIZE) return f
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        var last: Exception? = null
        for (url in MODEL_URLS) {
            val tmp = File(dir, "$MODEL_NAME.part")
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("ملف فاضي")
                    val total = body.contentLength()
                    var done = 0L
                    var shown = 0L
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                check(job)
                                if (total > 0 && done - shown > 2_000_000) {
                                    shown = done
                                    progress(job, "بينزّل موديل الذكاء الاصطناعي (مرة واحدة بس)…", done.toFloat() / total)
                                }
                            }
                        }
                    }
                }
                if (tmp.length() < MODEL_MIN_SIZE) throw IOException("الموديل نزل ناقص")
                tmp.renameTo(f)
                return f
            } catch (e: CancelledByUser) {
                tmp.delete(); throw e
            } catch (e: Exception) {
                tmp.delete(); last = e
            }
        }
        throw IOException("مقدرتش أنزّل الموديل: ${last?.message ?: ""}")
    }

    private fun separate(model: File, raw: File, outRaw: File, job: SepJob) {
        val frames = raw.length() / 8   // stereo float32
        if (frames <= 0) throw IOException("الملف مفيهوش صوت")
        val chunks = ((frames + MdxDsp.GEN - 1) / MdxDsp.GEN).toInt()
        // continue after an interruption: keep only whole finished parts
        val chunkBytes = MdxDsp.GEN.toLong() * 8
        val startChunk = minOf((if (outRaw.exists()) outRaw.length() / chunkBytes else 0L).toInt(), job.nextChunk, chunks)
        RandomAccessFile(outRaw, "rw").use { it.setLength(startChunk * chunkBytes) }
        if (startChunk >= chunks) return

        val env = OrtEnvironment.getEnvironment()
        progress(job, "بيجهّز الذكاء الاصطناعي…", startChunk.toFloat() / chunks)
        val session = createSession(env, model)
        val dsp = MdxDsp()
        val n = MdxDsp.CHUNK
        val l = FloatArray(n); val r = FloatArray(n)
        val oL = FloatArray(n); val oR = FloatArray(n)
        val input = FloatArray(4 * MdxDsp.PLANE)
        val output = FloatArray(4 * MdxDsp.PLANE)
        val readBuf = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN)
        val writeBuf = ByteBuffer.allocate(MdxDsp.GEN * 8).order(ByteOrder.LITTLE_ENDIAN)
        val started = System.currentTimeMillis()
        try {
            RandomAccessFile(raw, "r").use { inFile ->
                FileOutputStream(outRaw, true).use { out ->
                    for (k in startChunk until chunks) {
                        check(job)
                        // CHUNK frames starting TRIM before this part's new samples (zeros outside the song)
                        val start = k.toLong() * MdxDsp.GEN - MdxDsp.TRIM
                        java.util.Arrays.fill(l, 0f); java.util.Arrays.fill(r, 0f)
                        val from = maxOf(start, 0L)
                        val to = minOf(start + n, frames)
                        if (to > from) {
                            readBuf.clear()
                            inFile.seek(from * 8)
                            val bytes = ((to - from) * 8).toInt()
                            inFile.readFully(readBuf.array(), 0, bytes)
                            readBuf.limit(bytes)
                            var i = (from - start).toInt()
                            while (readBuf.remaining() >= 8) { l[i] = readBuf.float; r[i] = readBuf.float; i++ }
                        }
                        dsp.stft(l, r, input)
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 4, MdxDsp.DIM_F.toLong(), MdxDsp.DIM_T.toLong())).use { t ->
                            session.run(mapOf(session.inputNames.first() to t)).use { res ->
                                (res.get(0) as OnnxTensor).floatBuffer.get(output)
                            }
                        }
                        dsp.istft(output, oL, oR)
                        // keep the new part; voice = model output, music = original minus voice
                        val count = minOf(MdxDsp.GEN.toLong(), frames - k.toLong() * MdxDsp.GEN).toInt()
                        writeBuf.clear()
                        for (i in 0 until count) {
                            val vl = oL[MdxDsp.TRIM + i] * COMPENSATE
                            val vr = oR[MdxDsp.TRIM + i] * COMPENSATE
                            if (job.mode == SepMode.VOICE) { writeBuf.putFloat(vl); writeBuf.putFloat(vr) }
                            else { writeBuf.putFloat(l[MdxDsp.TRIM + i] - vl); writeBuf.putFloat(r[MdxDsp.TRIM + i] - vr) }
                        }
                        out.write(writeBuf.array(), 0, count * 8)
                        out.fd.sync()
                        val doneParts = k + 1 - startChunk
                        val elapsed = (System.currentTimeMillis() - started) / 1000.0
                        val eta = (elapsed / doneParts * (chunks - k - 1)).toLong()
                        set(job.id) { it.copy(nextChunk = k + 1) }
                        progress(job, "بيفصل الصوت عن الموسيقى… (${k + 1} من $chunks)", (k + 1f) / chunks, eta)
                    }
                }
            }
        } finally {
            session.close()
        }
    }

    private fun createSession(env: OrtEnvironment, model: File): OrtSession {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        fun opts(xnn: Boolean) = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (xnn) addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        }
        return try {
            env.createSession(model.path, opts(xnn = true))
        } catch (e: Exception) {
            env.createSession(model.path, opts(xnn = false))
        }
    }

    /** The ffmpeg bundled for yt-dlp. */
    private fun ffmpeg(args: List<String>, error: String) {
        val bin = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pkgs = File(noBackupFilesDir, "youtubedl-android/packages")
        val pb = ProcessBuilder(listOf(bin.path, "-nostdin", "-hide_banner", "-loglevel", "error") + args)
        pb.environment()["LD_LIBRARY_PATH"] = "${pkgs.path}/python/usr/lib:${pkgs.path}/ffmpeg/usr/lib"
        pb.redirectErrorStream(true)
        val p = pb.start()
        val log = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0) throw IOException("$error (${log.lines().lastOrNull { it.isNotBlank() }?.take(160) ?: code})")
    }

    private fun notification(text: String, progress: Float) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("فصل الموسيقى")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, (progress.coerceAtLeast(0f) * 100).toInt(), progress < 0)
        .setContentIntent(Notifications.openAppIntent(this, UiState.TAB_DOWNLOADS))
        .build()

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ADD = "com.ali.files.separate.ADD"
        const val CANCEL = "com.ali.files.separate.CANCEL"
        const val RESUME = "com.ali.files.separate.RESUME"
        private const val CHANNEL = "separate"
        private const val NOTE_ID = 7071
        private val MODEL_URLS = listOf(
            "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/Kim_Vocal_2.onnx",
            "https://huggingface.co/huangjackson/Kim_Vocal_2/resolve/main/Kim_Vocal_2.onnx",
        )
        private const val MODEL_NAME = "Kim_Vocal_2.onnx"
        private const val MODEL_MIN_SIZE = 60_000_000L
        /** volume correction recommended for this model */
        private const val COMPENSATE = 1.009f
    }
}
