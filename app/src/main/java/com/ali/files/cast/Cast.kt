package com.ali.files.cast

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import com.ali.files.player.Track
import com.ali.files.util.mimeOf
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Tells Google's Cast library to use the standard media receiver on the TV. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(c: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()
    override fun getAdditionalSessionProviders(c: Context) = null
}

/**
 * Chromecast / Google TV: plays the video on the TV. Files from the phone are served to the TV
 * by a tiny web server inside the app (same Wi-Fi only).
 */
object Cast {
    /** name of the TV we're showing on, null = not casting */
    val device = mutableStateOf<String?>(null)
    val playing = mutableStateOf(false)
    private var ctx: CastContext? = null
    private var pending: Pair<Track, Long>? = null
    private lateinit var app: Context

    fun available(c: Context): Boolean = context(c) != null

    private fun context(c: Context): CastContext? {
        ctx?.let { return it }
        app = c.applicationContext
        return runCatching { CastContext.getSharedInstance(app) }.getOrNull()?.also { cc ->
            ctx = cc
            cc.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        }
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) { device.value = s.castDevice?.friendlyName ?: "التلفزيون"; pending?.let { load(s, it.first, it.second) }; pending = null }
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { device.value = s.castDevice?.friendlyName ?: "التلفزيون" }
        override fun onSessionEnded(s: CastSession, error: Int) { device.value = null; playing.value = false; Server.stop() }
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStartFailed(s: CastSession, error: Int) { device.value = null }
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    /** Shows the list of TVs, then plays [t] there from [positionMs]. */
    fun castTrack(activityCtx: Context, t: Track, positionMs: Long): String? {
        val cc = context(activityCtx) ?: return "الإرسال للتلفزيون محتاج خدمات جوجل على الموبايل"
        val s = cc.sessionManager.currentCastSession
        if (s != null && s.isConnected) { load(s, t, positionMs); return null }
        pending = t to positionMs
        val themed = android.view.ContextThemeWrapper(activityCtx, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
        val dlg = androidx.mediarouter.app.MediaRouteChooserDialog(themed)
        cc.mergedSelector?.let { dlg.routeSelector = it }
        dlg.show()
        return null
    }

    private fun load(s: CastSession, t: Track, positionMs: Long) {
        val url = when (t.uri.scheme) {
            "http", "https" -> t.uri.toString()
            else -> Server.share(app, t.uri) ?: return
        }
        val meta = MediaMetadata(if (t.isVideo) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, t.title)
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mimeOf(t.uri.lastPathSegment ?: "").takeIf { it.startsWith("video") || it.startsWith("audio") } ?: if (t.isVideo) "video/mp4" else "audio/mpeg")
            .setMetadata(meta)
            .build()
        s.remoteMediaClient?.load(MediaLoadRequestData.Builder().setMediaInfo(info).setCurrentTime(positionMs).setAutoplay(true).build())
        playing.value = true
    }

    fun toggle() {
        val c = ctx?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        c.togglePlayback(); playing.value = !playing.value
    }

    fun stop() { ctx?.sessionManager?.endCurrentSession(true); device.value = null; Server.stop() }

    /** The tiny web server: serves one file to the TV, with seeking (Range requests). */
    private object Server {
        private var socket: ServerSocket? = null
        private var uri: Uri? = null
        private var token = ""
        private val pool = Executors.newCachedThreadPool()

        fun share(c: Context, u: Uri): String? {
            val ip = wifiIp() ?: return null
            uri = u
            token = java.util.UUID.randomUUID().toString().take(12)
            if (socket == null) {
                val ss = ServerSocket(0)
                socket = ss
                pool.execute {
                    while (!ss.isClosed) {
                        val client = runCatching { ss.accept() }.getOrNull() ?: break
                        pool.execute { runCatching { serve(c, client) }; runCatching { client.close() } }
                    }
                }
            }
            return "http://$ip:${socket!!.localPort}/$token/media"
        }

        fun stop() { runCatching { socket?.close() }; socket = null; uri = null }

        private fun serve(c: Context, s: Socket) {
            val input = s.getInputStream().bufferedReader()
            val first = input.readLine() ?: return
            var range: String? = null
            while (true) {
                val l = input.readLine() ?: break
                if (l.isEmpty()) break
                if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim()
            }
            val out = s.getOutputStream()
            val u = uri
            if (u == null || !first.contains("/$token/")) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
            val size = runCatching { c.contentResolver.openFileDescriptor(u, "r")?.use { it.statSize } }.getOrNull() ?: -1L
            val name = u.lastPathSegment ?: "media.mp4"
            val mime = mimeOf(name).ifBlank { "video/mp4" }
            var start = 0L
            var end = size - 1
            val partial = range != null && range.startsWith("bytes=") && size > 0
            if (partial) {
                val r = range!!.removePrefix("bytes=").substringBefore(',')
                start = r.substringBefore('-').toLongOrNull() ?: 0L
                end = r.substringAfter('-').toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            }
            val len = if (size > 0) end - start + 1 else -1
            val head = StringBuilder()
                .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                .append("Content-Type: $mime\r\nAccept-Ranges: bytes\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n")
            if (len >= 0) head.append("Content-Length: $len\r\n")
            if (partial) head.append("Content-Range: bytes $start-$end/$size\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray())
            if (first.startsWith("HEAD")) return
            val ins: InputStream = c.contentResolver.openInputStream(u) ?: return
            ins.use {
                var skip = start
                while (skip > 0) { val n = it.skip(skip); if (n <= 0) break; skip -= n }
                val buf = ByteArray(256 * 1024)
                var left = if (len >= 0) len else Long.MAX_VALUE
                while (left > 0) {
                    val n = it.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            out.flush()
        }

        private fun wifiIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("eth")) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()
    }
}
