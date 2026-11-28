package com.ali.files.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ali.files.library.Library
import com.ali.files.util.sanitizeFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** «حمّل نص الفيديو»: the video's captions turned into a readable Markdown file + a plain text file, saved in the library. */
object VideoText {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val order = listOf("ar-orig", "ar", "en-orig", "en")

    private class Cue(val startMs: Long, val endMs: Long, val text: String)

    private fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun cues(f: File): List<Cue> {
        val events = JSONObject(f.readText()).optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Cue>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val segs = e.optJSONArray("segs") ?: continue
            val sb = StringBuilder()
            for (j in 0 until segs.length()) sb.append(segs.getJSONObject(j).optString("utf8"))
            val text = sb.toString().replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty() || text == out.lastOrNull()?.text) continue
            val start = e.optLong("tStartMs")
            out += Cue(start, start + e.optLong("dDurationMs"), text)
        }
        return out
    }

    /** sentences joined into paragraphs: a new one after a pause, or when it gets long */
    private fun paragraphs(c: List<Cue>): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        var start = 0L; var end = 0L
        val acc = StringBuilder()
        for (q in c) {
            if (acc.isNotEmpty() && (q.startMs - end > 2500 || acc.length > 420)) { out += start to acc.toString().trim(); acc.clear() }
            if (acc.isEmpty()) start = q.startMs
            acc.append(q.text).append(' ')
            end = q.endMs
        }
        if (acc.isNotEmpty()) out += start to acc.toString().trim()
        return out
    }

    /** Fetches the captions and puts two files («.md» and «.txt») in the library folder «نصوص». Returns the language used. */
    suspend fun save(ctx: Context, meta: VideoMeta, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val work = File(ctx.cacheDir, "vtext_${System.currentTimeMillis()}")
        try {
            val files = VideoExtractor.fetchSubtitles(meta.url, headers, work)
            val lang = order.firstOrNull { it in files } ?: files.keys.firstOrNull() ?: throw IOException("الفيديو ده مالوش نص عند يوتيوب")
            val paras = paragraphs(cues(files.getValue(lang)))
            if (paras.isEmpty()) throw IOException("النص فاضي")
            val name = sanitizeFileName(meta.title).take(80).ifBlank { "نص الفيديو" }
            val md = buildString {
                append("# ").append(meta.title).append("\n\n")
                append("- الرابط: ").append(meta.url).append('\n')
                if (meta.uploader.isNotBlank()) append("- القناة: ").append(meta.uploader).append('\n')
                if (meta.durationSec > 0) append("- المدة: ").append(time(meta.durationSec * 1000)).append('\n')
                append("- لغة النص: ").append(lang.removeSuffix("-orig")).append(" (من يوتيوب، ممكن فيه أخطاء)\n\n---\n\n")
                paras.forEach { (t, p) -> append("**[").append(time(t)).append("]** ").append(p).append("\n\n") }
            }
            val txt = meta.title + "\n\n" + paras.joinToString("\n\n") { it.second } + "\n"
            val a = File(work, "$name.md").apply { writeText(md) }
            val b = File(work, "$name.txt").apply { writeText(txt) }
            Library.add(a, a.name, meta.url, "نصوص")
            Library.add(b, b.name, meta.url, "نصوص")
            lang
        } finally { work.deleteRecursively() }
    }

    /** next to a download: runs on its own and says when it's done */
    fun saveInBackground(ctx: Context, meta: VideoMeta, headers: Map<String, String>) {
        val app = ctx.applicationContext
        scope.launch {
            val msg = runCatching { save(app, meta, headers) }
                .fold({ "اتحفظ نص الفيديو في المكتبة ← نصوص" }, { "نص الفيديو: " + (it.message ?: "تعذر") })
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
        }
    }
}
