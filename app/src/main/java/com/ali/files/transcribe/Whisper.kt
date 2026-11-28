package com.ali.files.transcribe

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class Seg(val startMs: Long, val endMs: Long, val text: String)

/**
 * OpenAI Whisper running on the phone (ONNX, int8): audio → log-mel → encoder → greedy decoder with timestamps.
 * Works for the "base" and "small" exports of onnx-community (sizes are read from the model itself).
 */
class Whisper(dir: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val enc: OrtSession
    private val dec: OrtSession
    private val numMel: Int
    private val layers: Int
    private val heads: Int
    private val headDim: Int
    private val pastNames: List<String>
    private val presentNames: List<String>
    private val vocab: Array<ByteArray?>
    private val fb: Array<FloatArray>      // [mel][bin]
    private val cosT: Array<FloatArray>
    private val sinT: Array<FloatArray>
    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }

    init {
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        enc = env.createSession(File(dir, "encoder.onnx").path, opts)
        dec = env.createSession(File(dir, "decoder.onnx").path, OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
        numMel = ((enc.inputInfo["input_features"]!!.info as TensorInfo).shape[1]).toInt().let { if (it > 0) it else 80 }
        pastNames = dec.inputNames.filter { it.startsWith("past_key_values") }
        presentNames = dec.outputNames.filter { it.startsWith("present") }
        layers = pastNames.size / 4
        val shape = (dec.inputInfo[pastNames.first()]!!.info as TensorInfo).shape
        heads = shape[1].toInt(); headDim = shape[3].toInt()

        // token id → bytes (GPT-2 byte-level BPE)
        val u2b = HashMap<Char, Int>()
        val bs = ArrayList<Int>().apply { addAll(33..126); addAll(161..172); addAll(174..255) }
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        for (i in bs.indices) u2b[cs[i].toChar()] = bs[i]
        val obj = Json.parseToJsonElement(File(dir, "vocab.json").readText()).jsonObject
        vocab = arrayOfNulls(EOT + 1)
        for ((tok, id) in obj) {
            val i = id.jsonPrimitive.int
            if (i < vocab.size) vocab[i] = ByteArray(tok.length) { (u2b[tok[it]] ?: 63).toByte() }
        }

        // mel filter bank (Slaney scale, like librosa / Whisper)
        fun hz2mel(f: Double) = if (f >= 1000) 15 + ln(f / 1000) / (ln(6.4) / 27) else f / (200.0 / 3)
        fun mel2hz(m: Double) = if (m >= 15) 1000 * exp(ln(6.4) / 27 * (m - 15)) else m * (200.0 / 3)
        val bins = N_FFT / 2 + 1
        val pts = DoubleArray(numMel + 2) { mel2hz(hz2mel(0.0) + (hz2mel(SR / 2.0) - hz2mel(0.0)) * it / (numMel + 1)) }
        fb = Array(numMel) { m ->
            val lo = pts[m]; val c = pts[m + 1]; val hi = pts[m + 2]
            FloatArray(bins) { b ->
                val f = SR / 2.0 * b / (bins - 1)
                (max(0.0, min((f - lo) / (c - lo), (hi - f) / (hi - c))) * 2.0 / (hi - lo)).toFloat()
            }
        }
        cosT = Array(bins) { k -> FloatArray(N_FFT) { cos(2.0 * PI * k * it / N_FFT).toFloat() } }
        sinT = Array(bins) { k -> FloatArray(N_FFT) { sin(2.0 * PI * k * it / N_FFT).toFloat() } }
    }

    /** log-mel of one 30 s window ([a] has [len] real samples, the rest is silence) → numMel × 3000 */
    private fun logMel(a: FloatArray, len: Int): FloatArray {
        val bins = N_FFT / 2 + 1
        fun at(p: Int): Float {           // reflect-padded sample
            var i = p - N_FFT / 2
            if (i < 0) i = -i
            if (i >= CHUNK) i = 2 * (CHUNK - 1) - i
            return if (i < len) a[i] else 0f
        }
        val mel = FloatArray(numMel * FRAMES)
        val frame = FloatArray(N_FFT)
        val pow = FloatArray(bins)
        var mx = -1e9f
        for (t in 0 until FRAMES) {
            for (j in 0 until N_FFT) frame[j] = at(t * HOP + j) * window[j]
            for (k in 0 until bins) {
                var re = 0f; var im = 0f
                val c = cosT[k]; val s = sinT[k]
                for (j in 0 until N_FFT) { re += frame[j] * c[j]; im -= frame[j] * s[j] }
                pow[k] = re * re + im * im
            }
            for (m in 0 until numMel) {
                var sum = 0f
                val w = fb[m]
                for (k in 0 until bins) sum += pow[k] * w[k]
                val l = kotlin.math.log10(max(sum, 1e-10f))
                mel[m * FRAMES + t] = l
                if (l > mx) mx = l
            }
        }
        val floor = mx - 8f
        for (i in mel.indices) mel[i] = (max(mel[i], floor) + 4f) / 4f
        return mel
    }

    /** Transcribes one window (up to 30 s). Returns the tokens (timestamps included). */
    private fun decode(samples: FloatArray, len: Int, lang: Int, cancelled: () -> Boolean): List<Int> {
        val tokens = ArrayList<Int>()
        val feats = OnnxTensor.createTensor(env, FloatBuffer.wrap(logMel(samples, len)), longArrayOf(1, numMel.toLong(), FRAMES.toLong()))
        val encOut = enc.run(mapOf("input_features" to feats))
        feats.close()
        val hidden = encOut[0] as OnnxTensor
        val past = HashMap<String, OnnxTensor>()
        val toClose = ArrayList<OrtSession.Result>()
        var first: OrtSession.Result? = null
        var prev: OrtSession.Result? = null
        val dummy = ArrayList<OnnxTensor>()
        try {
            for (nme in pastNames) {
                val t = OnnxTensor.createTensor(env, FloatBuffer.allocate(heads * headDim), longArrayOf(1, heads.toLong(), 1, headDim.toLong()))
                dummy += t; past[nme] = t
            }
            var input = longArrayOf(SOT.toLong(), lang.toLong(), TRANSCRIBE.toLong())
            var useCache = false
            val maxTokens = 220
            var lastTs = TS0
            for (step in 0 until maxTokens) {
                if (cancelled()) break
                val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(input), longArrayOf(1, input.size.toLong()))
                val flag = OnnxTensor.createTensor(env, booleanArrayOf(useCache))
                val inputs = HashMap<String, OnnxTensor>(past)
                inputs["input_ids"] = ids; inputs["encoder_hidden_states"] = hidden; inputs["use_cache_branch"] = flag
                val r = dec.run(inputs)
                ids.close(); flag.close()
                // the previous step's results are no longer needed (the first one keeps the encoder's cache)
                if (prev != null && prev !== first) prev.close()
                prev = r; if (first == null) first = r
                @Suppress("UNCHECKED_CAST")
                val rows = (r[0] as OnnxTensor).value as Array<Array<FloatArray>>
                val logits = rows[0].last().copyOf()
                for (i in presentNames.indices) {
                    val nme = presentNames[i]
                    if (useCache && ".encoder." in nme) continue
                    past["past_key_values." + nme.removePrefix("present.")] = r[nme].get() as OnnxTensor
                }
                toClose += r

                // rules: no special tokens; timestamps come in pairs and never go back
                for (i in SOT..TS0 - 1) logits[i] = -1e9f
                for (s in SUPPRESS) if (s < logits.size) logits[s] = -1e9f
                val lastIsTs = tokens.isNotEmpty() && tokens.last() >= TS0
                val prevIsTs = tokens.size < 2 || tokens[tokens.size - 2] >= TS0
                if (tokens.isEmpty()) { for (i in 0 until TS0) logits[i] = -1e9f; for (i in TS0 + 51 until logits.size) logits[i] = -1e9f }
                else if (lastIsTs) {
                    if (prevIsTs) { for (i in TS0 until logits.size) logits[i] = -1e9f }
                    else { for (i in 0 until EOT) logits[i] = -1e9f }
                }
                for (i in TS0 until min(lastTs, logits.size)) logits[i] = -1e9f
                // a timestamp is preferred when its total probability beats the best word
                var tsMax = -1e9f; var txMax = -1e9f
                for (i in TS0 until logits.size) tsMax = max(tsMax, logits[i])
                for (i in 0 until TS0) txMax = max(txMax, logits[i])
                if (tokens.isNotEmpty() && !(lastIsTs && !prevIsTs)) {
                    var tsSum = 0.0; val mx = max(tsMax, txMax)
                    var txSum = 0.0
                    for (i in TS0 until logits.size) tsSum += exp((logits[i] - mx).toDouble())
                    for (i in 0 until TS0) txSum += exp((logits[i] - mx).toDouble())
                    if (tsSum > txSum) for (i in 0 until TS0) logits[i] = -1e9f
                }
                var best = 0
                for (i in logits.indices) if (logits[i] > logits[best]) best = i
                if (best == EOT) break
                tokens += best
                if (best >= TS0) lastTs = best
                input = longArrayOf(best.toLong()); useCache = true
                if (repeating(tokens)) break
            }
        } finally {
            toClose.forEach { runCatching { it.close() } }
            dummy.forEach { runCatching { it.close() } }
            encOut.close()
        }
        return tokens
    }

    /** the same few words over and over = the model is stuck (usually on music or noise) */
    private fun repeating(t: List<Int>): Boolean {
        for (n in 1..8) {
            val need = n * 5
            if (t.size < need) continue
            val tail = t.subList(t.size - need, t.size)
            if ((n until need).all { tail[it] == tail[it - n] }) return true
        }
        return false
    }

    private fun text(ids: List<Int>): String {
        val out = java.io.ByteArrayOutputStream()
        for (i in ids) if (i < vocab.size) vocab[i]?.let { out.write(it) }
        return out.toString("UTF-8").trim()
    }

    /**
     * Goes through a 16 kHz mono f32le file window by window. [onProgress] gets 0..1.
     * Windows that are silent are skipped (they'd only produce invented text).
     */
    fun transcribe(raw: File, lang: Int = LANG_AR, cancelled: () -> Boolean, onProgress: (Float) -> Unit): List<Seg> {
        val total = (raw.length() / 4).toInt()
        val segs = ArrayList<Seg>()
        val buf = FloatArray(CHUNK)
        var pos = 0
        java.io.RandomAccessFile(raw, "r").use { f ->
            val bytes = ByteArray(CHUNK * 4)
            while (pos < total - SR / 2 && !cancelled()) {
                onProgress(pos.toFloat() / total)
                val n = min(CHUNK, total - pos)
                f.seek(pos.toLong() * 4)
                f.readFully(bytes, 0, n * 4)
                val bb = java.nio.ByteBuffer.wrap(bytes, 0, n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                bb.get(buf, 0, n)
                var e = 0.0
                for (i in 0 until n) e += buf[i] * buf[i]
                val rms = kotlin.math.sqrt(e / n)
                var advance = CHUNK
                if (rms > 0.003) {
                    val tokens = decode(buf, n, lang, cancelled)
                    val base = pos * 1000L / SR
                    var start = -1L
                    val cur = ArrayList<Int>()
                    fun flush(end: Long) {
                        val s = text(cur)
                        if (s.isNotEmpty() && start >= 0) segs += Seg(base + start, base + max(end, start + 500), s)
                        cur.clear()
                    }
                    for (t in tokens) {
                        if (t >= TS0) {
                            val ms = (t - TS0) * 20L
                            if (cur.isNotEmpty()) flush(ms)
                            start = ms
                        } else cur += t
                    }
                    if (cur.isNotEmpty()) flush(n * 1000L / SR)
                    // like Whisper: if the window ended in the middle (one timestamp), continue from there
                    val lastIsTs = tokens.isNotEmpty() && tokens.last() >= TS0
                    val prevIsTs = tokens.size >= 2 && tokens[tokens.size - 2] >= TS0
                    if (lastIsTs && !prevIsTs) {
                        val a = (tokens.last() - TS0) * 20L * SR / 1000
                        if (a > SR) advance = a.toInt()
                    }
                }
                java.util.Arrays.fill(buf, 0f)
                pos += advance
            }
        }
        onProgress(1f)
        return segs
    }

    override fun close() { runCatching { enc.close() }; runCatching { dec.close() } }

    companion object {
        const val SR = 16000
        const val N_FFT = 400
        const val HOP = 160
        const val CHUNK = 480000
        const val FRAMES = 3000
        const val EOT = 50257
        const val SOT = 50258
        const val LANG_AR = 50272
        const val TRANSCRIBE = 50359
        const val TS0 = 50364
        /** tokens Whisper never writes (symbols / music marks) */
        private val SUPPRESS = intArrayOf(1, 2, 7, 8, 9, 10, 14, 25, 26, 27, 28, 29, 31, 58, 59, 60, 61, 62, 63, 90, 91, 92, 93, 359, 503, 522, 542, 873, 893, 902, 918, 922, 931, 1350, 1853, 1982, 2460, 2627, 3246, 3253, 3268, 3536, 3846, 3961, 4183, 4667, 6585, 6647, 7273, 9061, 9383, 10428, 10929, 11938, 12033, 12331, 12562, 13793, 14157, 14635, 15265, 15618, 16553, 16604, 18362, 18956, 20075, 21675, 22520, 26130, 26161, 26435, 28279, 29464, 31650, 32302, 32470, 36865, 42863, 47425, 49870, 50254)
    }
}
