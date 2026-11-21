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
import com.ali.files.download.Downloader
import com.ali.files.download.VideoExtractor
import com.ali.files.library.LibFile
import com.ali.files.library.Library
import com.ali.files.util.FileKind
import com.ali.files.util.Notifications
import com.ali.files.ui.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext

enum class SepMode(val label: String, val suffix: String) {
    /** keep the voice, remove the music */
    VOICE("بدون موسيقى", "بدون موسيقى"),
    /** keep the music, remove the voice */
    MUSIC("الموسيقى بس", "موسيقى بس"),
}

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
)

/**
 * Removes the music from a song / video (or keeps only the music) with the MDX-Net «Kim Vocal 2»
 * model, entirely on the phone. Work happens in [SeparateService] so it continues in the background.
 */
object Separator {
    private val MODEL_URLS = listOf(
        "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/Kim_Vocal_2.onnx",
        "https://huggingface.co/huangjackson/Kim_Vocal_2/resolve/main/Kim_Vocal_2.onnx",
    )
    private const val MODEL_NAME = "Kim_Vocal_2.onnx"
    private const val MODEL_MIN_SIZE = 60_000_000L
    /** volume correction recommended for this model */
    private const val COMPENSATE = 1.009f

    private val _jobs = MutableStateFlow<List<SepJob>>(emptyList())
    val jobs: StateFlow<List<SepJob>> = _jobs
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun start(c: Context, f: LibFile, mode: SepMode) {
        val job = SepJob(System.currentTimeMillis(), f.path, f.title, mode, f.kind == FileKind.VIDEO)
        _jobs.update { it + job }
        ContextCompat.startForegroundService(c, Intent(c, SeparateService::class.java))
    }

    fun cancel(id: Long) {
        cancelled.add(id)
        _jobs.update { l -> l.map { if (it.id == id && !it.done) it.copy(done = true, error = "اتلغى") else it } }
    }

    fun dismiss(id: Long) = _jobs.update { l -> l.filterNot { it.id == id } }

    internal fun next(): SepJob? = _jobs.value.firstOrNull { !it.done }

    private fun set(id: Long, f: (SepJob) -> SepJob) = _jobs.update { l -> l.map { if (it.id == id) f(it) else it } }

    // ------------------------------------------------------------ the work

    internal suspend fun process(c: Context, job: SepJob) {
        val work = File(c.cacheDir, "separate").apply { deleteRecursively(); mkdirs() }
        try {
            val model = ensureModel(c, job)
            check(job)
            set(job.id) { it.copy(state = "بيجهّز الصوت…", progress = -1f) }
            VideoExtractor.awaitReady()
            val raw = File(work, "in.raw")
            ffmpeg(c, listOf("-y", "-i", job.path, "-vn", "-ac", "2", "-ar", "44100", "-f", "f32le", raw.path), "تعذر قراءة الصوت من الملف")
            check(job)

            val outRaw = File(work, "out.raw")
            separate(model, raw, outRaw, job)
            raw.delete()
            check(job)

            set(job.id) { it.copy(state = "بيحفظ الملف…", progress = -1f, etaSec = -1) }
            val src = File(job.path)
            val dest: File = if (job.isVideo) {
                val ext = if (src.extension.lowercase() == "mp4") "mp4" else "mkv"
                val out = Library.uniqueFile("${job.title} (${job.mode.suffix}).$ext")
                ffmpeg(c, listOf(
                    "-y", "-i", job.path, "-f", "f32le", "-ar", "44100", "-ac", "2", "-i", outRaw.path,
                    "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", out.path,
                ), "تعذر حفظ الفيديو")
                out
            } else {
                val mp3 = Library.uniqueFile("${job.title} (${job.mode.suffix}).mp3")
                try {
                    ffmpeg(c, listOf("-y", "-f", "f32le", "-ar", "44100", "-ac", "2", "-i", outRaw.path, "-c:a", "libmp3lame", "-q:a", "2", mp3.path), "تعذر حفظ الصوت")
                    mp3
                } catch (e: IOException) {
                    val m4a = Library.uniqueFile("${job.title} (${job.mode.suffix}).m4a")
                    ffmpeg(c, listOf("-y", "-f", "f32le", "-ar", "44100", "-ac", "2", "-i", outRaw.path, "-c:a", "aac", "-b:a", "256k", m4a.path), "تعذر حفظ الصوت")
                    m4a
                }
            }
            Library.refresh()
            set(job.id) { it.copy(done = true, state = "خلص ✅", progress = 1f, output = dest.absolutePath) }
        } catch (e: CancelledByUser) {
            set(job.id) { it.copy(done = true, error = "اتلغى") }
        } catch (e: Throwable) {
            set(job.id) { it.copy(done = true, error = e.message ?: "حصل خطأ") }
        } finally {
            work.deleteRecursively()
            cancelled.remove(job.id)
        }
    }

    private class CancelledByUser : Exception()

    private suspend fun check(job: SepJob) {
        if (job.id in cancelled || !coroutineContext.isActive) throw CancelledByUser()
    }

    /** Downloads the AI model the first time (about 67 MB). */
    private suspend fun ensureModel(c: Context, job: SepJob): File {
        val dir = File(c.filesDir, "models").apply { mkdirs() }
        val f = File(dir, MODEL_NAME)
        if (f.exists() && f.length() > MODEL_MIN_SIZE) return f
        var last: Exception? = null
        for (url in MODEL_URLS) {
            val tmp = File(dir, "$MODEL_NAME.part")
            try {
                Downloader.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("ملف فاضي")
                    val total = body.contentLength()
                    var done = 0L
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                check(job)
                                if (total > 0) set(job.id) { it.copy(state = "بينزّل موديل الذكاء الاصطناعي (مرة واحدة بس)…", progress = done.toFloat() / total) }
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

    private suspend fun separate(model: File, raw: File, outRaw: File, job: SepJob) {
        val frames = raw.length() / 8   // stereo float32
        if (frames <= 0) throw IOException("الملف مفيهوش صوت")
        val chunks = ((frames + MdxDsp.GEN - 1) / MdxDsp.GEN).toInt()
        val env = OrtEnvironment.getEnvironment()
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
                BufferedOutputStream(outRaw.outputStream(), 1 shl 20).use { out ->
                    for (k in 0 until chunks) {
                        check(job)
                        // read CHUNK frames starting TRIM before this chunk's new part (zeros outside the song)
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
                        val elapsed = (System.currentTimeMillis() - started) / 1000.0
                        val eta = (elapsed / (k + 1) * (chunks - k - 1)).toLong()
                        set(job.id) { it.copy(state = "بيفصل الصوت عن الموسيقى… (${k + 1} من $chunks)", progress = (k + 1f) / chunks, etaSec = eta) }
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

    // ------------------------------------------------------------ ffmpeg (the one bundled for yt-dlp)

    private fun ffmpeg(c: Context, args: List<String>, error: String) {
        val bin = File(c.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pkgs = File(c.noBackupFilesDir, "youtubedl-android/packages")
        val pb = ProcessBuilder(listOf(bin.path, "-nostdin", "-hide_banner", "-loglevel", "error") + args)
        pb.environment()["LD_LIBRARY_PATH"] = "${pkgs.path}/python/usr/lib:${pkgs.path}/ffmpeg/usr/lib"
        pb.redirectErrorStream(true)
        val p = pb.start()
        val log = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0) throw IOException("$error (${log.lines().lastOrNull { it.isNotBlank() }?.take(160) ?: code})")
    }
}

/** Runs the separation jobs in the background with a progress notification. */
class SeparateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "فصل الموسيقى", NotificationManager.IMPORTANCE_LOW))
        val first = notification("بيبدأ…", -1f)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTE_ID, first)
        if (worker?.isActive != true) worker = scope.launch {
            // keep the notification in step with the job
            val watcher = launch {
                Separator.jobs.collect { list ->
                    list.firstOrNull { !it.done }?.let { j ->
                        nm.notify(NOTE_ID, notification("${j.title} — ${j.state}", j.progress))
                    }
                }
            }
            while (true) {
                val job = Separator.next() ?: break
                Separator.process(applicationContext, job)
                val done = Separator.jobs.value.firstOrNull { it.id == job.id }
                if (done?.error == null && done?.output != null) {
                    nm.notify(job.id.toInt(), NotificationCompat.Builder(this@SeparateService, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle("خلص: ${job.title} (${job.mode.label})")
                        .setContentText("موجود في المكتبة")
                        .setContentIntent(Notifications.openAppIntent(this@SeparateService, UiState.TAB_DOWNLOADS))
                        .setAutoCancel(true)
                        .build())
                }
            }
            watcher.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
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
        private const val CHANNEL = "separate"
        private const val NOTE_ID = 7071
    }
}
