package com.ali.files.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ali.files.download.DownloadRequest
import com.ali.files.util.AppSettings
import com.ali.files.util.FileStore
import com.ali.files.util.mimeOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File

@Serializable
data class Bookmark(val title: String, val url: String)

@Serializable
data class HistoryEntry(val title: String, val url: String, val time: Long)

data class DetectedMedia(val url: String, val kind: String)

data class LongPress(val url: String, val isImage: Boolean, val incognito: Boolean, val referer: String?)

class BrowserTab(val id: Long, val incognito: Boolean) {
    lateinit var webView: WebView
    val title = mutableStateOf("")
    val url = mutableStateOf("")
    val progress = mutableIntStateOf(100)
    val canBack = mutableStateOf(false)
    val canForward = mutableStateOf(false)
    val blocked = mutableIntStateOf(0)
    val desktop = mutableStateOf(false)
    val media = mutableStateListOf<DetectedMedia>()
    val secure: Boolean get() = url.value.startsWith("https://")

    fun addMedia(u: String, kind: String) {
        if (media.size < 60 && media.none { it.url == u }) media.add(DetectedMedia(u, kind))
    }
}

/**
 * Owns all browser tabs (WebViews). Lives as long as the activity; the activity handles
 * configuration changes itself so WebViews are not recreated on rotation.
 */
class BrowserController(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private var nextId = 1L

    val tabs = mutableStateListOf<BrowserTab>()
    val currentId = mutableLongStateOf(-1L)
    val current: BrowserTab? get() = tabs.firstOrNull { it.id == currentId.longValue }

    val bookmarks = mutableStateListOf<Bookmark>()
    val history = mutableStateListOf<HistoryEntry>()

    var onDownloadRequest: ((DownloadRequest) -> Unit)? = null
    var onLongPress: ((LongPress) -> Unit)? = null
    var launchFileChooser: ((Intent) -> Unit)? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val bookmarksFile get() = File(activity.filesDir, "bookmarks.json")
    private val historyFile get() = File(activity.filesDir, "history.json")
    private val sessionFile get() = File(activity.filesDir, "session.json")

    init {
        runCatching { bookmarks.addAll(json.decodeFromString<List<Bookmark>>(bookmarksFile.readText())) }
        runCatching { history.addAll(json.decodeFromString<List<HistoryEntry>>(historyFile.readText())) }
        val urls = runCatching { json.decodeFromString<List<String>>(sessionFile.readText()) }.getOrDefault(emptyList())
        if (urls.isEmpty()) newTab(AppSettings.homeUrl()) else urls.forEach { newTab(it, select = false) }
        if (currentId.longValue < 0) tabs.firstOrNull()?.let { currentId.longValue = it.id }
    }

    // ------------------------------------------------------------ tabs

    fun newTab(url: String? = AppSettings.homeUrl(), incognito: Boolean = false, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(nextId++, incognito)
        tab.webView = createWebView(tab)
        tabs.add(tab)
        if (select || currentId.longValue < 0) currentId.longValue = tab.id
        if (url != null) tab.webView.loadUrl(url)
        return tab
    }

    fun select(id: Long) { currentId.longValue = id }

    fun closeTab(id: Long) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs.removeAt(idx)
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        if (tabs.isEmpty()) newTab()
        else if (currentId.longValue == id) currentId.longValue = tabs[(idx - 1).coerceAtLeast(0)].id
    }

    fun closeAll() {
        tabs.toList().forEach { closeTab(it.id) }
    }

    fun load(input: String) {
        val tab = current ?: newTab(null)
        tab.webView.loadUrl(toUrl(input))
    }

    fun toUrl(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return AppSettings.homeUrl()
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t) || t.startsWith("about:")) return t
        // local devices (router, inverter…) usually speak plain http
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(t) || t.startsWith("localhost")) return "http://$t"
        if (!t.contains(' ') && Regex("^[^\\s]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(t)) return "https://$t"
        return AppSettings.searchUrl(t)
    }

    fun setDesktop(tab: BrowserTab, on: Boolean) {
        tab.desktop.value = on
        tab.webView.settings.userAgentString = if (on) DESKTOP_UA else null
        tab.webView.settings.useWideViewPort = true
        tab.webView.reload()
    }

    fun sniffDom(tab: BrowserTab) {
        tab.webView.evaluateJavascript(MediaSniffer.DOM_SCRIPT) { result ->
            runCatching {
                if (result != null && result != "null") {
                    val arr = JSONArray(result)
                    for (i in 0 until arr.length()) {
                        val u = arr.getString(i)
                        tab.addMedia(u, MediaSniffer.kind(u) ?: "وسائط")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ bookmarks / history

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    fun toggleBookmark(tab: BrowserTab) {
        val u = tab.url.value
        if (u.isBlank()) return
        val existing = bookmarks.firstOrNull { it.url == u }
        if (existing != null) bookmarks.remove(existing) else bookmarks.add(0, Bookmark(tab.title.value.ifBlank { u }, u))
        runCatching { bookmarksFile.writeText(json.encodeToString(bookmarks.toList())) }
    }

    fun removeBookmark(b: Bookmark) {
        bookmarks.remove(b)
        runCatching { bookmarksFile.writeText(json.encodeToString(bookmarks.toList())) }
    }

    private fun addHistory(title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        if (history.firstOrNull()?.url == url) return
        history.add(0, HistoryEntry(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (history.size > 500) history.removeAt(history.size - 1)
        runCatching { historyFile.writeText(json.encodeToString(history.toList())) }
    }

    fun clearHistory() {
        history.clear()
        historyFile.delete()
    }

    fun clearBrowsingData() {
        clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach { it.webView.clearCache(true); it.webView.clearFormData() }
    }

    fun saveSession() {
        val urls = tabs.filter { !it.incognito }.map { it.url.value }.filter { it.startsWith("http") }
        runCatching { sessionFile.writeText(json.encodeToString(urls)) }
        CookieManager.getInstance().flush()
    }

    fun destroy() {
        saveSession()
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
    }

    // ------------------------------------------------------------ fullscreen video / file chooser

    val fullscreen = mutableStateOf(false)

    fun exitFullscreen() {
        val v = fullscreenView ?: return
        (activity.window.decorView as FrameLayout).removeView(v)
        fullscreenView = null
        fullscreen.value = false
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    fun onFileChosen(resultCode: Int, data: Intent?) {
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        fileCallback = null
    }

    // ------------------------------------------------------------ downloads from pages

    private fun headersFor(url: String, ua: String?, referer: String?): Map<String, String> = buildMap {
        put("User-Agent", ua ?: WebSettings.getDefaultUserAgent(activity))
        CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
        if (!referer.isNullOrBlank()) put("Referer", referer)
    }

    fun requestDownload(url: String, tab: BrowserTab?, contentDisposition: String? = null, mime: String? = null, size: Long = -1) {
        when {
            url.startsWith("blob:") -> toast("الملف ده متولّد جوه الصفحة (blob) ومينفعش يتحمل مباشرة — جرّب زرار الوسائط")
            url.startsWith("data:") -> saveDataUrl(url)
            else -> {
                val name = URLUtil.guessFileName(url, contentDisposition, mime)
                val ua = tab?.webView?.settings?.userAgentString
                onDownloadRequest?.invoke(DownloadRequest(url, name, mime, size, headersFor(url, ua, tab?.url?.value)))
            }
        }
    }

    private fun saveDataUrl(url: String) {
        runCatching {
            val header = url.substringBefore(',')
            val mime = header.removePrefix("data:").substringBefore(';').ifBlank { "application/octet-stream" }
            val payload = url.substringAfter(',')
            val bytes = if (header.contains(";base64")) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            val name = "file_${System.currentTimeMillis()}.$ext"
            FileStore.createInDownloads(activity, name, mimeOf(name).takeIf { it != "application/octet-stream" } ?: mime) { it.write(bytes) }
            toast("اتحفظ في Download/AliFiles")
        }.onFailure { toast("تعذر حفظ الملف") }
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ WebView setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: BrowserTab): WebView {
        val wv = WebView(activity)
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (tab.incognito) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !tab.incognito)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase() ?: return false
                if (scheme in setOf("http", "https", "about", "data", "blob", "javascript", "file")) return false
                // intent:, market:, whatsapp:, tel:, mailto: … → hand to the right app
                try {
                    val intent = if (scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    try {
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        intent.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) }
                    }
                } catch (_: Exception) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                tab.url.value = url ?: ""
                tab.media.clear()
                tab.blocked.intValue = 0
                tab.progress.intValue = 5
            }

            override fun onPageFinished(view: WebView, url: String?) {
                tab.url.value = url ?: tab.url.value
                tab.title.value = view.title ?: tab.url.value
                tab.canBack.value = view.canGoBack()
                tab.canForward.value = view.canGoForward()
                if (!tab.incognito) addHistory(tab.title.value, tab.url.value)
                sniffDom(tab)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != null) tab.url.value = url
                tab.canBack.value = view.canGoBack()
                tab.canForward.value = view.canGoForward()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (AppSettings.adBlockFast && !request.isForMainFrame && AdBlocker.isBlocked(request.url.host)) {
                    main.post { tab.blocked.intValue++ }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                MediaSniffer.kind(url)?.let { k -> main.post { tab.addMedia(url, k) } }
                return null
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress.intValue = newProgress }
            override fun onReceivedTitle(view: WebView, title: String?) { tab.title.value = title ?: "" }

            // pop-up blocker: only windows opened by a real tap become new tabs
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) { tab.blocked.intValue++; return false }
                val t = newTab(null, tab.incognito)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = t.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabs.firstOrNull { it.webView === window }?.let { closeTab(it.id) }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return }
                fullscreenView = view
                fullscreenCallback = callback
                fullscreen.value = true
                (activity.window.decorView as FrameLayout).addView(
                    view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            override fun onHideCustomView() { exitFullscreen() }

            override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val launcher = launchFileChooser
                return if (launcher != null) {
                    try { launcher(fileChooserParams.createIntent()); true } catch (_: Exception) { fileCallback = null; false }
                } else false
            }
        }

        wv.setDownloadListener { url, _, contentDisposition, mimetype, contentLength ->
            requestDownload(url, tab, contentDisposition, mimetype, contentLength)
        }

        wv.setOnLongClickListener {
            val r = wv.hitTestResult
            when (r.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.IMAGE_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val extra = r.extra
                    if (extra.isNullOrBlank()) false
                    else {
                        onLongPress?.invoke(LongPress(extra, r.type != WebView.HitTestResult.SRC_ANCHOR_TYPE, tab.incognito, tab.url.value))
                        true
                    }
                }
                else -> false
            }
        }
        return wv
    }

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
