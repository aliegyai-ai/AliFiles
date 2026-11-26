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

data class LongPress(val url: String, val isImage: Boolean, val referer: String?)

/** A site asking for the camera / microphone / location; [answer] gets the user's choice. */
data class SitePermission(val host: String, val kinds: List<String>, val answer: (Boolean) -> Unit)

class BrowserTab(val id: Long) {
    lateinit var webView: WebView
    /** the WebView wrapped in its own pull-to-refresh frame (one per tab, so switching tabs never breaks it) */
    lateinit var frame: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    /** the finger is on a list that scrolls by itself inside the page (e.g. a YouTube playlist) */
    @Volatile var touchInInnerList = false
    /** when the finger last touched the page (a site can't open the download sheet without you touching it) */
    @Volatile var lastTouch = 0L
    /** the page covered itself with a «log in» window (we hid it) */
    val loginWall = mutableStateOf(false)
    val title = mutableStateOf("")
    val url = mutableStateOf("")
    val progress = mutableIntStateOf(100)
    val canBack = mutableStateOf(false)
    val canForward = mutableStateOf(false)
    val blocked = mutableIntStateOf(0)
    val desktop = mutableStateOf(false)
    val media = mutableStateListOf<DetectedMedia>()
    /** set when a <video> in the page starts playing — shows the floating download button */
    val playing = mutableStateOf(false)
    var lastVideoSrc: String = ""
    var lastVideoPage: String = ""
    val secure: Boolean get() = url.value.startsWith("https://")
    /** true while the tab shows the app's own start page instead of a website */
    val home = mutableStateOf(false)
    /** small screenshot for the tab switcher */
    val thumb = mutableStateOf<android.graphics.Bitmap?>(null)

    /** host of the page in this tab, read by the ad blocker on a background thread */
    @Volatile var pageHost: String? = null
    /** opened by the page itself (window.open / target=_blank) — closed if it turns out to be an ad */
    @Volatile var fromPopup = false
    /** the tab this one was opened from — closing this one goes back there */
    var openerId: Long = -1
    /** restored from last time but not loaded yet (loads when you open the tab) */
    var pendingUrl: String? = null
    /** a site opened from the start page: its first page in this tab's history (back from it → start page) */
    var homeBarrier = -1
    /** a site is loading from the start page — the start page stays until the new page shows (no flash of the old one) */
    val openingFromHome = mutableStateOf(false)
    /** find in page: (current match, total) */
    val findResult = mutableStateOf(0 to 0)

    /** how many videos (not images/sounds) the page has — for the badge on the download icon */
    val videoCount: Int get() = media.count { it.kind == "فيديو" || it.kind == "HLS" }

    fun addMedia(u: String, kind: String) {
        if (media.size < 60 && media.none { it.url == u }) media.add(DetectedMedia(u, kind))
    }
}

/**
 * Owns all browser tabs (WebViews). Lives as long as the activity; the activity handles
 * configuration changes itself so WebViews are not recreated on rotation.
 */
/** Marks a tab that shows the start page (speed dial, clipboard link, recent downloads). */
const val HOME = "about:home"
private const val PRIVATE_PROFILE = "private"

class BrowserController(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val docStartScript: Boolean by lazy {
        runCatching { androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var nextId = 1L

    val tabs = mutableStateListOf<BrowserTab>()
    /** the bottom toolbar slides away while scrolling down a page, and back on scrolling up */
    val barsHidden = mutableStateOf(false)
    /** the address bar is being typed in (then the toolbar must stay on screen) */
    val addressFocused = mutableStateOf(false)
    /** keyboard is open for a box inside the page — the toolbar stays put so the page isn't resized under it */
    @Volatile var imeOpen = false
    val currentId = mutableLongStateOf(-1L)
    val current: BrowserTab? get() = tabs.firstOrNull { it.id == currentId.longValue }
    fun homeShowing() = current?.home?.value != false

    private fun noPullRefresh(url: String?): Boolean {
        val h = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return h.endsWith("youtube.com") || h.endsWith("tiktok.com") || h.endsWith("instagram.com") || h.endsWith("facebook.com")
    }

    /** the browser is showing just a video in a small floating window */
    val pipMode = mutableStateOf(false)
    /** we opened another screen on purpose (file picker, share, another app) */
    @Volatile var awayOnPurpose = false

    /** Clears the sites' cached files (images, scripts) for the normal tabs; logins and history stay. */
    fun clearWebCache() {
        val wv = tabs.firstOrNull()?.webView
        if (wv != null) wv.clearCache(true)
        else runCatching { WebView(activity).apply { clearCache(true); destroy() } }
    }

    val bookmarks = mutableStateListOf<Bookmark>()
    val history = mutableStateListOf<HistoryEntry>()

    var onDownloadRequest: ((DownloadRequest) -> Unit)? = null
    var onVideoRequest: ((com.ali.files.download.VideoRequest) -> Unit)? = null
    var onLongPress: ((LongPress) -> Unit)? = null
    var launchFileChooser: ((Intent) -> Unit)? = null
    /** asks Android for camera / microphone / location permission; the callback gets true if granted */
    var requestAndroidPermissions: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null
    /** a site wants the camera / microphone / location — the screen shows a question */
    val sitePermission = mutableStateOf<SitePermission?>(null)
    private val allowedSites = HashMap<String, MutableSet<String>>()
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val bookmarksFile get() = File(activity.filesDir, "bookmarks.json")
    private val historyFile get() = File(activity.filesDir, "history.json")
    private val sessionFile get() = File(activity.filesDir, "session.json")

    init {
        // the old private-browsing space is gone: remove what it may have left on disk
        runCatching { if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
        runCatching { bookmarks.addAll(json.decodeFromString<List<Bookmark>>(bookmarksFile.readText())) }
        runCatching { history.addAll(json.decodeFromString<List<HistoryEntry>>(historyFile.readText())) }
        val urls = runCatching { json.decodeFromString<List<String>>(sessionFile.readText()) }.getOrDefault(emptyList())
        if (urls.isEmpty()) newTab(HOME) else urls.forEach { newTab(it, select = false) }
        if (currentId.longValue < 0) tabs.firstOrNull()?.let { currentId.longValue = it.id }
        current?.let { ensureLoaded(it) }
    }

    // ------------------------------------------------------------ tabs

    fun newTab(url: String? = HOME, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(nextId++)
        tab.webView = createWebView(tab)
        tab.frame = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(activity).apply {
            addView(tab.webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setColorSchemeColors(0xFFD4A94F.toInt())
            setProgressBackgroundColorSchemeColor(0xFF221F19.toInt())
            setOnRefreshListener { tab.webView.reload() }
            // pull-to-refresh only from the top of the page itself — never while scrolling a list
            // inside the page (YouTube's playlist, comments…), which used to hijack the scroll
            setOnChildScrollUpCallback { _, _ ->
                // YouTube's lists (playlist, comments) scroll inside the page and the finger reaches us
                // before the page can say so → pull-to-refresh stole the gesture. YouTube has its own refresh.
                tab.webView.scrollY > 0 || tab.touchInInnerList || noPullRefresh(tab.url.value)
            }
        }
        tabs.add(tab)
        if (select || currentId.longValue < 0) {
            current?.let { captureThumb(it) }
            currentId.longValue = tab.id
        }
        when {
            url == null -> {}
            url == HOME -> tab.home.value = true
            // restored tabs in the background wait until you open them (faster start, less data)
            !select -> { tab.pendingUrl = url; tab.url.value = url; tab.title.value = url.substringAfter("://").substringBefore('/') }
            else -> tab.webView.loadUrl(url)
        }
        return tab
    }

    private fun ensureLoaded(tab: BrowserTab) {
        val u = tab.pendingUrl ?: return
        tab.pendingUrl = null
        tab.webView.loadUrl(u)
    }

    fun select(id: Long) {
        current?.let { captureThumb(it) }
        currentId.longValue = id
        tabs.firstOrNull { it.id == id }?.let { ensureLoaded(it) }
        barsHidden.value = false
    }

    /** Shows the start page in the current tab (the website stays behind it, so «back» returns to it). */
    fun goHome() { current?.let { captureThumb(it); it.openingFromHome.value = false; it.home.value = true } }

    /** Leaves the start page and shows the website that was open in this tab, if any. */
    fun leaveHome(tab: BrowserTab): Boolean {
        if (!tab.home.value || tab.url.value.isBlank()) return false
        tab.home.value = false
        return true
    }

    /** Takes a small picture of the tab's page for the tab switcher. */
    fun captureThumb(tab: BrowserTab) {
        if (tab.home.value) { tab.thumb.value = null; return }
        val v = tab.webView
        if (v.width <= 0 || v.height <= 0 || !v.isAttachedToWindow) return
        runCatching {
            // copy what's on screen (the page is drawn by the GPU, so drawing it into a bitmap comes out blank)
            val loc = IntArray(2).also { v.getLocationInWindow(it) }
            val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            val bmp = android.graphics.Bitmap.createBitmap((v.width * 0.35f).toInt(), (v.height * 0.35f).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(activity.window, rect, bmp, { r ->
                if (r == android.view.PixelCopy.SUCCESS) tab.thumb.value = bmp
            }, main)
        }
    }

    fun closeTab(id: Long) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs.removeAt(idx)
        BgMedia.tabClosed(tab)
        (tab.frame.parent as? ViewGroup)?.removeView(tab.frame)
        tab.webView.stopLoading()
        tab.webView.destroy()
        if (currentId.longValue == id) {
            val opener = tabs.firstOrNull { it.id == tab.openerId }
            when {
                opener != null -> select(opener.id)   // back to the tab the link was opened from
                tabs.isNotEmpty() -> select(tabs.last().id)
                else -> newTab(HOME)
            }
        }
    }

    fun closeAll() { tabs.toList().forEach { closeTab(it.id) } }

    /**
     * «جرّب من غير تسجيل»: 1) clean the page again, 2) Instagram → the post's embed page, Facebook → the light
     * site, 3) otherwise hand the page to the downloader (works for public videos with no account).
     */
    fun tryWithoutLogin(tab: BrowserTab) {
        val now = tab.url.value
        tab.webView.evaluateJavascript("window.__aliWall && window.__aliWall()", null)
        main.postDelayed({
            if (!tabs.contains(tab)) return@postDelayed
            val alt = LoginWall.embedUrl(now) ?: LoginWall.lightUrl(now)
            when {
                !tab.loginWall.value -> toast("اتشالت نافذة التسجيل")
                alt != null && alt != now -> { toast("بجرّب نسخة بتشتغل من غير حساب…"); tab.loginWall.value = false; tab.webView.loadUrl(alt) }
                com.ali.files.download.VideoExtractor.isSingleVideoPage(now) -> { toast("هجرّب أجيب الفيديو مباشرة…"); requestVideo(tab, now) }
                else -> toast("الموقع مش بيبعت المحتوى ده لغير المسجّلين")
            }
        }, 700)
    }

    fun load(input: String) {
        val tab = current ?: newTab(null)
        val u = toUrl(input)
        if (u == HOME) { tab.home.value = true; return }
        tab.pendingUrl = null
        if (tab.home.value) {
            // from the start page: this site starts a fresh trail in the tab, and the old page isn't shown meanwhile
            tab.homeBarrier = if (tab.url.value.isBlank()) 0 else tab.webView.copyBackForwardList().currentIndex + 1
            if (tab.url.value.isBlank()) tab.home.value = false else tab.openingFromHome.value = true
        }
        tab.webView.loadUrl(u)
    }

    /** Back inside a tab: the previous page, but never past the start page the site was opened from. */
    fun back(tab: BrowserTab) {
        val idx = tab.webView.copyBackForwardList().currentIndex
        if (tab.homeBarrier >= 0 && idx <= tab.homeBarrier) goHome()
        else if (tab.webView.canGoBack()) tab.webView.goBack()
        else goHome()
    }

    private fun showOpenedPage(tab: BrowserTab) {
        if (tab.openingFromHome.value) { tab.openingFromHome.value = false; tab.home.value = false }
    }

    fun toUrl(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return HOME
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

    /** Adds a site to the start page's speed dial (bookmarks). */
    fun addBookmark(title: String, input: String) {
        val u = toUrl(input)
        if (u == HOME || bookmarks.any { it.url == u }) return
        bookmarks.add(0, Bookmark(title.ifBlank { u.substringAfter("://").substringBefore('/') }, u))
        runCatching { bookmarksFile.writeText(json.encodeToString(bookmarks.toList())) }
    }

    fun removeBookmark(b: Bookmark) {
        bookmarks.remove(b)
        runCatching { bookmarksFile.writeText(json.encodeToString(bookmarks.toList())) }
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun saveHistory() {
        val snapshot = history.toList()
        io.execute { runCatching { historyFile.writeText(json.encodeToString(snapshot)) } }
    }

    private fun addHistory(title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        val first = history.firstOrNull()
        if (first?.url == url) {
            if (title.isNotBlank() && first.title != title) { history[0] = first.copy(title = title); saveHistory() }
            return
        }
        history.add(0, HistoryEntry(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (history.size > 500) history.removeAt(history.size - 1)
        saveHistory()
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
        val urls = tabs.map { if (it.home.value) HOME else it.url.value }.filter { it.startsWith("http") || it == HOME }
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
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
                if (mime == "application/x-bittorrent" || name.endsWith(".torrent", true)) {
                    toast("ملفات التورنت مش مدعومة")
                    return
                }
                onDownloadRequest?.invoke(DownloadRequest(url, name, mime, size, headersFor(url, ua, tab?.url?.value)))
            }
        }
    }

    /** Hands the current page to yt-dlp (YouTube, Facebook, TikTok…), with the browser's login cookies. */
    fun requestVideo(tab: BrowserTab, page: String? = null) {
        val url = page?.takeIf { it.startsWith("http") } ?: tab.url.value
        if (!url.startsWith("http")) return
        onVideoRequest?.invoke(videoRequestFor(tab, url))
    }

    /** What we know about the video from the page itself (title, YouTube picture) + the login cookies. */
    private fun videoRequestFor(tab: BrowserTab, url: String) = com.ali.files.download.VideoRequest(
        url, headersFor(url, tab.webView.settings.userAgentString, null),
        title = tab.title.value.removeSuffix(" - YouTube").takeIf { it.isNotBlank() && url == tab.url.value },
        thumbnail = com.ali.files.download.VideoExtractor.youtubeId(url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
    )

    /** On a video page, start reading its qualities after a moment, so the download sheet opens ready. */
    private fun schedulePrefetch(tab: BrowserTab, url: String) {
        if (!com.ali.files.download.VideoExtractor.isSingleVideoPage(url)) return
        main.postDelayed({
            if (tab.url.value == url && tabs.contains(tab)) com.ali.files.download.VideoExtractor.prefetch(videoRequestFor(tab, url))
        }, 1500)
    }

    /** A ⬇ button on a video (or the floating one) was tapped. */
    fun downloadVideo(tab: BrowserTab, src: String = tab.lastVideoSrc, page: String = tab.lastVideoPage) {
        // on YouTube & co. always the video that's open now (the remembered one can be the previous video)
        val now = tab.url.value
        val pageUrl = when {
            com.ali.files.download.VideoExtractor.isSingleVideoPage(now) -> now
            com.ali.files.download.VideoExtractor.isSingleVideoPage(page) -> page
            com.ali.files.download.VideoExtractor.isVideoPage(now) -> { toast("افتح الفيديو نفسه الأول، وبعدين دوس تحميل"); return }
            else -> page.ifBlank { now }
        }
        val direct = src.startsWith("http") && !com.ali.files.download.VideoExtractor.isVideoPage(pageUrl) &&
            (MediaSniffer.kind(src) != null || !src.contains("googlevideo"))
        if (direct) requestDownload(src, tab) else requestVideo(tab, pageUrl)
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

    /** Settings switch «dark websites» — applies to the open tabs at once. */
    fun applyDarkSites() {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) return
        tabs.forEach { t -> runCatching { androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(t.webView.settings, AppSettings.darkSites.value) } }
    }

    /** Long-press «open in a private tab». */

    /** Asks the user (once per site per session) and then Android, before a site gets the camera / mic / location. */
    private fun askSite(origin: String, kinds: List<String>, tab: BrowserTab, done: (Boolean) -> Unit) {
        val host = runCatching { Uri.parse(origin).host }.getOrNull() ?: origin
        val known = allowedSites[host]
        if (known != null && known.containsAll(kinds)) { androidGrant(kinds, done); return }
        sitePermission.value = SitePermission(host, kinds) { ok ->
            sitePermission.value = null
            if (!ok) { done(false); return@SitePermission }
            allowedSites.getOrPut(host) { HashSet() }.addAll(kinds)
            androidGrant(kinds, done)
        }
    }

    private fun androidGrant(kinds: List<String>, done: (Boolean) -> Unit) {
        val perms = kinds.flatMap {
            when (it) {
                "camera" -> listOf(android.Manifest.permission.CAMERA)
                "mic" -> listOf(android.Manifest.permission.RECORD_AUDIO)
                else -> listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }.filter { androidx.core.content.ContextCompat.checkSelfPermission(activity, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (perms.isEmpty()) { done(true); return }
        val ask = requestAndroidPermissions ?: run { done(false); return }
        ask(perms.toTypedArray(), done)
    }

    // ------------------------------------------------------------ WebView setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: BrowserTab): WebView {
        val wv = PageView(activity).apply { playing = { tab.playing.value } }
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
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        // dark websites when the phone is dark (only pages that don't have their own dark look)
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
            runCatching { androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, AppSettings.darkSites.value) }
        }
        wv.setFindListener { active, total, _ -> tab.findResult.value = (if (total > 0) active + 1 else 0) to total }

        // ⬇ buttons on every video (all frames, injected before the page's own scripts when supported)
        wv.addJavascriptInterface(VideoButtons.Bridge(
            onDownload = { src, page -> tab.lastVideoSrc = src; tab.lastVideoPage = page; downloadVideo(tab, src, page) },
            onPlaying = { src, page -> tab.lastVideoSrc = src; tab.lastVideoPage = page; tab.playing.value = true },
        ), VideoButtons.BRIDGE)
        // ad blocker: hides ad boxes and skips YouTube ads (runs before the page's own scripts)
        wv.addJavascriptInterface(AdFilter.Bridge(), AdFilter.BRIDGE)
        // videos keep playing in the background, with controls in the notification
        wv.addJavascriptInterface(BgMedia.Bridge(activity, tab), BgMedia.BRIDGE)
        wv.addJavascriptInterface(InnerScroll.Bridge(tab), InnerScroll.BRIDGE)
        wv.addJavascriptInterface(LoginWall.Bridge(tab), LoginWall.BRIDGE)
        if (docStartScript) {
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, AdFilter.SCRIPT, setOf("*")) }
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, VideoButtons.SCRIPT, setOf("*")) }
            if (AppSettings.bgPlay.value) runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, BgMedia.SCRIPT, setOf("*")) }
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, InnerScroll.SCRIPT, setOf("*")) }
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, LoginWall.SCRIPT, setOf("*")) }
        }

        // the toolbar stays put: hiding it resized the page mid-scroll, and lists inside pages
        // (YouTube playlists) stopped after moving a little.

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase() ?: return false
                // redirects / pop-up tabs to ad & pop-under sites are stopped (a real tap on a link still works)
                if ((scheme == "http" || scheme == "https") && request.isForMainFrame && AppSettings.adBlockFast &&
                    (!request.hasGesture() || tab.fromPopup) && AdFilter.isAdPage(u.host) &&
                    !(tab.pageHost?.let { SiteAllow.isAllowed(it) } ?: false)
                ) {
                    tab.blocked.intValue++
                    AdStats.add(1)
                    if (tab.fromPopup && !view.canGoBack()) main.post { closeTab(tab.id) }
                    return true
                }
                if (scheme == "magnet") { toast("لينكات التورنت مش مدعومة"); return true }
                if (scheme in setOf("http", "https", "about", "data", "blob", "javascript", "file")) return false
                // intent:, market:, whatsapp:, tel:, mailto: … → hand to the right app
                try {
                    val intent = if (scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    try {
                        awayOnPurpose = true
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
                tab.pageHost = runCatching { Uri.parse(url).host }.getOrNull()
                tab.media.clear()
                tab.loginWall.value = false
                tab.playing.value = false
                tab.lastVideoSrc = ""
                tab.lastVideoPage = ""
                tab.blocked.intValue = 0
                tab.progress.intValue = 5
                if (tab.id == currentId.longValue) barsHidden.value = false
            }

            // the new page has drawn something → now swap the start page for it
            override fun onPageCommitVisible(view: WebView, url: String?) { showOpenedPage(tab) }

            override fun onPageFinished(view: WebView, url: String?) {
                showOpenedPage(tab)
                if (tab.frame.isRefreshing) tab.frame.isRefreshing = false
                tab.url.value = url ?: tab.url.value
                tab.title.value = view.title ?: tab.url.value
                tab.canBack.value = view.canGoBack()
                tab.canForward.value = view.canGoForward()
                addHistory(tab.title.value, tab.url.value)
                sniffDom(tab)
                if (!docStartScript) {
                    view.evaluateJavascript(AdFilter.SCRIPT, null)
                    view.evaluateJavascript(VideoButtons.SCRIPT, null)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != null) {
                    // sites like YouTube change page without loading a new one: forget the previous video
                    if (url.substringBefore('#') != tab.url.value.substringBefore('#')) {
                        tab.media.clear()
                        tab.playing.value = false
                        tab.lastVideoSrc = ""
                        tab.lastVideoPage = ""
                    }
                    tab.url.value = url
                    schedulePrefetch(tab, url)
                    addHistory(view.title ?: "", url)
                }
                tab.canBack.value = view.canGoBack()
                tab.canForward.value = view.canGoForward()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (!request.isForMainFrame || error.errorCode !in ErrorPage.codes) return
                val failing = request.url.toString()
                if (failing.startsWith("data:")) return
                view.loadDataWithBaseURL(failing, ErrorPage.html(error.errorCode, failing), "text/html", "utf-8", failing)
            }

            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                val failing = error.url ?: return
                if (failing != view.url && failing != tab.url.value) return
                view.loadDataWithBaseURL(failing, ErrorPage.html(android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, failing), "text/html", "utf-8", failing)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (AppSettings.adBlockFast && !request.isForMainFrame && AdFilter.shouldBlock(url, request.url.host, tab.pageHost)) {
                    main.post { tab.blocked.intValue++ }
                    AdStats.add(1)
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                MediaSniffer.kind(url)?.let { k -> main.post { tab.addMedia(url, k) } }
                return null
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress.intValue = newProgress }
            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title.value = title ?: ""
                if (!title.isNullOrBlank() && history.firstOrNull()?.url == tab.url.value) addHistory(title, tab.url.value)
            }

            // a site asks for the location (maps, weather…)
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                askSite(origin, listOf("location"), tab) { ok -> callback.invoke(origin, ok, false) }
            }

            // a site asks for the camera / microphone (calls, voice notes…)
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val wanted = request.resources.mapNotNull {
                    when (it) {
                        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
                        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "mic"
                        else -> null
                    }
                }
                if (wanted.isEmpty()) { request.deny(); return }
                askSite(request.origin.toString(), wanted, tab) { ok ->
                    if (ok) request.grant(request.resources.filter {
                        it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }.toTypedArray()) else request.deny()
                }
            }

            // pop-up blocker: only windows opened by a real tap become new tabs
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) { tab.blocked.intValue++; AdStats.add(1); return false }
                val t = newTab(null)
                t.fromPopup = true
                t.openerId = tab.id
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
                val frame = FrameLayout(activity)
                frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val d = activity.resources.displayMetrics.density
                val btn = android.widget.TextView(activity).apply {
                    text = "\u2B07"
                    textSize = 22f
                    setTextColor(0xFF1A1408.toInt())
                    gravity = android.view.Gravity.CENTER
                    contentDescription = "تحميل الفيديو"
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xF2D4A94F.toInt())
                    }
                    setOnClickListener { downloadVideo(tab) }
                }
                frame.addView(btn, FrameLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt(), android.view.Gravity.TOP or android.view.Gravity.END).apply {
                    val m = (20 * d).toInt(); setMargins(m, m, m, m)
                })
                // ⟳ turns the picture (sideways by default, like a TV)
                val rotate = android.widget.TextView(activity).apply {
                    text = "\u27F3"
                    textSize = 22f
                    setTextColor(android.graphics.Color.WHITE)
                    gravity = android.view.Gravity.CENTER
                    contentDescription = "لف الشاشة"
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0x99000000.toInt())
                    }
                    setOnClickListener {
                        activity.requestedOrientation =
                            if (activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
                frame.addView(rotate, FrameLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt(), android.view.Gravity.TOP or android.view.Gravity.START).apply {
                    val m = (20 * d).toInt(); setMargins(m, m, m, m)
                })
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                fullscreenView = frame
                fullscreenCallback = callback
                fullscreen.value = true
                (activity.window.decorView as FrameLayout).addView(
                    frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
                    try { awayOnPurpose = true; launcher(fileChooserParams.createIntent()); true } catch (_: Exception) { fileCallback = null; false }
                } else false
            }
        }

        wv.setOnTouchListener { _, ev -> if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) tab.lastTouch = System.currentTimeMillis(); false }
        wv.setDownloadListener { url, _, contentDisposition, mimetype, contentLength ->
            // a site that starts a download by itself (a redirect, a script) is ignored; only after you touched the page
            if (System.currentTimeMillis() - tab.lastTouch > 12_000) { toast("موقع حاول يحمّل ملف لوحده — اتجاهل"); return@setDownloadListener }
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
                        onLongPress?.invoke(LongPress(extra, r.type != WebView.HitTestResult.SRC_ANCHOR_TYPE, tab.url.value))
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
