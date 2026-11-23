package com.ali.files.browser

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.torproject.jni.TorService
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Tor inside the app: hides your real address from websites and from your internet provider.
 * When it's on, every page of the app (Android's WebView only allows one proxy for all of them),
 * downloads and yt-dlp go through it.
 */
object TorManager {
    enum class State { OFF, STARTING, ON, ERROR }

    val state = mutableStateOf(State.OFF)
    val message = mutableStateOf("")
    @Volatile var socksPort = 0
        private set
    val on: Boolean get() = state.value == State.ON

    private lateinit var app: Context
    private var service: TorService? = null
    private val main = Handler(Looper.getMainLooper())

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; off("Tor اتقفل") }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getStringExtra(TorService.EXTRA_STATUS)) {
                TorService.STATUS_ON -> main.postDelayed({ ready() }, 300)
                TorService.STATUS_OFF -> if (state.value != State.OFF) off("")
            }
        }
    }

    val supported: Boolean
        get() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)

    fun start(c: Context) {
        if (state.value == State.STARTING || state.value == State.ON) return
        app = c.applicationContext
        if (!supported) { state.value = State.ERROR; message.value = "المتصفح المدمج في موبايلك قديم ومش بيدعم Tor — حدّث «Android System WebView» من المتجر"; return }
        state.value = State.STARTING
        message.value = "بيوصل لشبكة Tor… (ممكن ياخد دقيقة)"
        ContextCompat.registerReceiver(app, receiver, IntentFilter(TorService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        app.bindService(Intent(app, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
        // give up if it doesn't connect in 2 minutes (Tor blocked on this network)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 120_000)
    }

    private val timeout = Runnable {
        if (state.value == State.STARTING) { stop(); state.value = State.ERROR; message.value = "مقدرتش أوصل لـ Tor — ممكن يكون مقفول على الشبكة دي" }
    }

    private fun ready() {
        if (state.value != State.STARTING) return
        val port = service?.socksPort ?: 0
        if (port <= 0) { main.postDelayed({ ready() }, 500); return }
        socksPort = port
        val done = Runnable {
            state.value = State.ON
            message.value = "متوصّل بـ Tor — عنوانك الحقيقي مستخبي"
        }
        try {
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:$port").build(),
                { it.run() }, done,
            )
        } catch (e: Exception) {
            // older WebViews: Tor's HTTP tunnel (works for https sites)
            runCatching {
                ProxyController.getInstance().setProxyOverride(
                    ProxyConfig.Builder().addProxyRule("127.0.0.1:${service?.httpTunnelPort ?: 8118}").build(),
                    { it.run() }, done,
                )
            }.onFailure { stop(); state.value = State.ERROR; message.value = "تعذر تشغيل Tor على المتصفح" }
        }
    }

    fun stop() {
        if (state.value == State.OFF) return
        off("")
    }

    private fun off(msg: String) {
        main.removeCallbacks(timeout)
        socksPort = 0
        state.value = State.OFF
        message.value = msg
        if (supported) runCatching { ProxyController.getInstance().clearProxyOverride({ it.run() }, {}) }
        if (::app.isInitialized) {
            runCatching { app.unbindService(conn) }
            runCatching { app.unregisterReceiver(receiver) }
            runCatching { app.stopService(Intent(app, TorService::class.java)) }
        }
        service = null
    }

    /** For OkHttp (downloads): through Tor while it's on, direct otherwise. */
    val proxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> =
            if (socksPort > 0) listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))) else listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }

    /** For yt-dlp. */
    fun ytdlpProxy(): String? = if (socksPort > 0) "socks5://127.0.0.1:$socksPort" else null
}
