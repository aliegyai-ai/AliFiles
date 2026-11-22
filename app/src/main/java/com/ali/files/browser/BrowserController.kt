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

/** A site asking for the camera / microphone / location; [answer] gets the user's choice. */
data class SitePermission(val host: String, val kinds: List<String>, val answer: (Boolean) -> Unit)

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

    // ------------------------------------------------------------ private (incognito) space
    /** true while the user is in the private space: its own tabs, purple look, nothing saved */
    val privateMode = mutableStateOf(false)
    /** the tabs of the space being shown (normal or private) */
    val visibleTabs: List<BrowserTab> get() = tabs.filter { it.incognito == privateMode.value }
    private var lastNormalId = -1L
    private var lastPrivateId = -1L

    /** Private tabs get their own WebView profile (own cookies, storage, cache) when the phone supports it. */
    private val profilesSupported: Boolean by lazy {
        runCatching { androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    }

    fun cookieManagerFor(tab: BrowserTab?): CookieManager =
        if (tab?.incognito == true && profilesSupported)
            runCatching { androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE).cookieManager }.getOrDefault(CookieManager.getInstance())
        else CookieManager.getInstance()

    fun enterPrivate() {
        if (privateMode.value) return
        current?.let { captureThumb(it); lastNormalId = it.id }
        privateMode.value = true
        val t = tabs.firstOrNull { it.incognito && it.id == lastPrivateId } ?: tabs.lastOrNull { it.incognito }
        if (t != null) currentId.longValue = t.id else newTab(HOME, incognito = true)
        barsHidden.value = false
    }

    /** Back to normal tabs; the private tabs stay open (until [closePrivate]). */
    fun exitPrivate() {
        if (!privateMode.value) return
        current?.let { captureThumb(it); lastPrivateId = it.id }
        privateMode.value = false
        val t = tabs.firstOrNull { !it.incognito && it.id == lastNormalId } ?: tabs.lastOrNull { !it.incognito }
        if (t != null) currentId.longValue = t.id else newTab(HOME)
        barsHidden.value = false
    }

    /** Closes every private tab and wipes everything they left behind (cookies, storage, cache). */
    fun closePrivate() {
        val wasPrivate = privateMode.value
        tabs.filter { it.incognito }.forEach { t ->
            tabs.remove(t)
            (t.webView.parent as? ViewGroup)?.removeView(t.webView)
            t.webView.stopLoading()
            t.webView.clearCache(true)
            t.webView.clearFormData()
            t.webView.clearHistory()
            t.webView.destroy()
        }
        lastPrivateId = -1L
        if (wasPrivate) {
            privateMode.value = false
            val t = tabs.firstOrNull { it.id == lastNormalId } ?: tabs.lastOrNull()
            if (t != null) currentId.longValue = t.id else newTab(HOME)
        }
        // the profile can only be deleted once its WebViews are really gone
        main.postDelayed({ wipePrivateProfile() }, 500)
    }

    private fun wipePrivateProfile() {
        if (!profilesSupported || tabs.any { it.incognito }) return
        runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
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
        // anything a private session left on disk (app was killed while private tabs were open) goes first
        wipePrivateProfile()
        runCatching { bookmarks.addAll(json.decodeFromString<List<Bookmark>>(bookmarksFile.readText())) }
        runCatching { history.addAll(json.decodeFromString<List<HistoryEntry>>(historyFile.readText())) }
        val urls = runCatching { json.decodeFromString<List<String>>(sessionFile.readText()) }.getOrDefault(emptyList())
        if (urls.isEmpty()) newTab(HOME) else urls.forEach { newTab(it, select = false) }
        if (currentId.longValue < 0) tabs.firstOrNull()?.let { currentId.longValue = it.id }
        current?.let { ensureLoaded(it) }
    }

    // ------------------------------------------------------------ tabs

    fun newTab(url: String? = HOME, incognito: Boolean = false, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(nextId++, incognito)
        tab.webView = createWebView(tab)
        tabs.add(tab)
        if (select || currentId.longValue < 0) {
            current?.let { captureThumb(it) }
            if (incognito && !privateMode.value) current?.let { lastNormalId = it.id }
            currentId.longValue = tab.id
            privateMode.value = incognito
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
        tabs.firstOrNull { it.id == id }?.let { privateMode.value = it.incognito; ensureLoaded(it) }
        barsHidden.value = false
    }

    /** Shows the start page in the current tab (the website stays behind it, so «back» returns to it). */
    fun goHome() { current?.let { captureThumb(it); it.home.value = true } }

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
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        val same = tabs.filter { it.incognito == tab.incognito }
        if (currentId.longValue == id) {
            val opener = same.firstOrNull { it.id == tab.openerId }
            when {
                opener != null -> select(opener.id)   // back to the tab the link was opened from
                same.isNotEmpty() -> select(same.last().id)
                tab.incognito -> closePrivate() // last private tab closed → wipe and go back to normal tabs
                else -> newTab(HOME)
            }
        } else if (tab.incognito && same.isEmpty()) {
            main.postDelayed({ wipePrivateProfile() }, 500)
        }
    }

    /** Closes all tabs of the space being shown. */
    fun closeAll() {
        if (privateMode.value) { closePrivate(); return }
        tabs.filter { !it.incognito }.forEach { closeTab(it.id) }
    }

    fun load(input: String) {
        val tab = current ?: newTab(null)
        val u = toUrl(input)
        if (u == HOME) { tab.home.value = true; return }
        tab.home.value = false
        tab.webView.loadUrl(u)
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
        val urls = tabs.filter { !it.incognito }.map { if (it.home.value) HOME else it.url.value }.filter { it.startsWith("http") || it == HOME }
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
        cookieManagerFor(current).getCookie(url)?.let { put("Cookie", it) }
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
        if (tab.incognito || !com.ali.files.download.VideoExtractor.isSingleVideoPage(url)) return
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
    fun openPrivate(url: String) { newTab(url, incognito = true) }

    /** Asks the user (once per site per session) and then Android, before a site gets the camera / mic / location. */
    private fun askSite(origin: String, kinds: List<String>, tab: BrowserTab, done: (Boolean) -> Unit) {
        val host = runCatching { Uri.parse(origin).host }.getOrNull() ?: origin
        val known = allowedSites[host]
        if (known != null && known.containsAll(kinds)) { androidGrant(kinds, done); return }
        sitePermission.value = SitePermission(host, kinds) { ok ->
            sitePermission.value = null
            if (!ok) { done(false); return@SitePermission }
            if (!tab.incognito) allowedSites.getOrPut(host) { HashSet() }.addAll(kinds)
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
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (tab.incognito) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        if (tab.incognito && profilesSupported) {
            // separate cookie jar / storage / cache for private tabs; must be set before the first load
            runCatching {
                androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
                androidx.webkit.WebViewCompat.setProfile(wv, PRIVATE_PROFILE)
            }
        }
        cookieManagerFor(tab).setAcceptThirdPartyCookies(wv, !tab.incognito)
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
        if (docStartScript) {
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, AdFilter.SCRIPT, setOf("*")) }
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, VideoButtons.SCRIPT, setOf("*")) }
        }

        // hide the toolbar while reading down the page, show it again when scrolling up
        wv.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (tab.id != currentId.longValue || imeOpen) return@setOnScrollChangeListener
            val dy = y - oldY
            when {
                y < 60 -> if (barsHidden.value) barsHidden.value = false
                dy > 18 -> if (!barsHidden.value) barsHidden.value = true
                dy < -18 -> if (barsHidden.value) barsHidden.value = false
            }
        }

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
                tab.pageHost = runCatching { Uri.parse(url).host }.getOrNull()
                tab.media.clear()
                tab.playing.value = false
                tab.lastVideoSrc = ""
                tab.lastVideoPage = ""
                tab.blocked.intValue = 0
                tab.progress.intValue = 5
                if (tab.id == currentId.longValue) barsHidden.value = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                tab.url.value = url ?: tab.url.value
                tab.title.value = view.title ?: tab.url.value
                tab.canBack.value = view.canGoBack()
                tab.canForward.value = view.canGoForward()
                if (!tab.incognito) addHistory(tab.title.value, tab.url.value)
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
                    if (!tab.incognito) addHistory(view.title ?: "", url)
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
                if (!tab.incognito && !title.isNullOrBlank() && history.firstOrNull()?.url == tab.url.value) addHistory(title, tab.url.value)
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
                val t = newTab(null, tab.incognito)
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
