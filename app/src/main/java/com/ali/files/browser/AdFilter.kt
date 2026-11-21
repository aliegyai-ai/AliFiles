package com.ali.files.browser

import android.content.Context
import android.webkit.JavascriptInterface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.ali.files.download.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.Locale

/**
 * The ad blocker. Uses the same public filter lists as uBlock Origin / AdBlock:
 *  - EasyList (ads), EasyPrivacy (trackers), Liste AR (Arabic sites), Fanboy Cookie (cookie banners),
 *    Peter Lowe's ad-server list.
 * From them it builds:
 *  - a set of blocked domains (requests to them never leave the phone), plus exception domains
 *  - CSS that hides ad boxes inside pages (generic rules + rules for specific sites)
 * The lists ship inside the APK (fresh from the build) and update themselves every week.
 */
object AdFilter {
    private val lists = listOf(
        "easylist" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy" to "https://easylist.to/easylist/easyprivacy.txt",
        "liste_ar" to "https://easylist-downloads.adblockplus.org/Liste_AR.txt",
        "cookies" to "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
        "pgl" to "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
    )
    private const val WEEK = 7 * 24 * 3600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context

    @Volatile private var blockDomains: Set<String> = BuiltinAds.hosts
    @Volatile private var allowDomains: Set<String> = emptySet()
    @Volatile private var genericCss: String = BuiltinAds.GENERIC_CSS
    @Volatile private var siteSelectors: Map<String, List<String>> = emptyMap()

    /** for the settings / ad sheet */
    val ruleCount = mutableIntStateOf(BuiltinAds.hosts.size)
    val lastUpdate = mutableLongStateOf(0L)
    val updating = mutableStateOf(false)

    private val filterDir get() = File(app.filesDir, "filters")

    fun init(c: Context) {
        app = c.applicationContext
        scope.launch {
            load()
            val sp = app.getSharedPreferences("adfilter", Context.MODE_PRIVATE)
            lastUpdate.longValue = sp.getLong("updated", 0)
            if (System.currentTimeMillis() - lastUpdate.longValue > WEEK) update()
        }
    }

    /** Downloads fresh lists (keeps the old ones if a download fails). */
    suspend fun update(): Boolean = withContext(Dispatchers.IO) {
        if (updating.value) return@withContext false
        updating.value = true
        var ok = 0
        filterDir.mkdirs()
        for ((name, url) in lists) {
            runCatching {
                Downloader.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val text = r.body?.string() ?: error("empty")
                    if (text.length < 1000) error("too small")
                    File(filterDir, "$name.txt").writeText(text)
                    ok++
                }
            }
        }
        if (ok > 0) {
            load()
            val now = System.currentTimeMillis()
            app.getSharedPreferences("adfilter", Context.MODE_PRIVATE).edit().putLong("updated", now).apply()
            lastUpdate.longValue = now
        }
        updating.value = false
        ok > 0
    }

    /** Reads every list (downloaded copy if there is one, else the one inside the APK) and builds the rules. */
    private fun load() {
        val block = HashSet<String>(BuiltinAds.hosts)
        val allow = HashSet<String>()
        val generic = LinkedHashSet<String>()
        val site = HashMap<String, MutableList<String>>()
        for ((name, _) in lists) {
            val text = runCatching { File(filterDir, "$name.txt").takeIf { it.exists() }?.readText() }.getOrNull()
                ?: runCatching { app.assets.open("filters/$name.txt").bufferedReader().use { it.readText() } }.getOrNull()
                ?: continue
            text.lineSequence().forEach { parse(it.trim(), block, allow, generic, site) }
        }
        blockDomains = block
        allowDomains = allow
        genericCss = buildCss(BuiltinAds.GENERIC_SELECTORS + generic)
        siteSelectors = site
        ruleCount.intValue = block.size + generic.size + site.values.sumOf { it.size }
    }

    private fun parse(line: String, block: MutableSet<String>, allow: MutableSet<String>, generic: MutableSet<String>, site: MutableMap<String, MutableList<String>>) {
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
        // hosts-file format: "0.0.0.0 ads.example.com"
        if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
            val h = line.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').lowercase(Locale.US)
            if (h.contains('.') && h != "localhost") block.add(h)
            return
        }
        // element hiding: "example.com,foo.org##.ad-box" or "##.ad-box"
        val hide = line.indexOf("##")
        if (hide >= 0) {
            val domains = line.substring(0, hide)
            val sel = line.substring(hide + 2)
            if (sel.isEmpty() || !safeSelector(sel)) return
            if (domains.isEmpty()) generic.add(sel)
            else domains.split(',').forEach { d ->
                if (d.isNotEmpty() && d[0] != '~') site.getOrPut(d.lowercase(Locale.US)) { mutableListOf() }.add(sel)
            }
            return
        }
        if (line.contains("#@#") || line.contains("#?#") || line.contains("#$#") || line.contains("#%#")) return
        // network rules: only whole-domain ones ("||ads.example.com^"), so a normal site is never cut in half
        val exception = line.startsWith("@@")
        val rule = if (exception) line.substring(2) else line
        if (!rule.startsWith("||")) return
        val body = rule.substring(2).substringBefore('$')
        val opts = rule.substringAfter('$', "")
        val end = body.indexOfFirst { it == '^' || it == '/' || it == '*' || it == '|' }
        val domain = (if (end < 0) body else body.substring(0, end)).lowercase(Locale.US)
        val rest = if (end < 0) "" else body.substring(end)
        if (rest != "" && rest != "^" && rest != "^|") return
        if (!domain.contains('.') || domain.contains('*')) return
        // rules limited to some sites, or to first-party only, are skipped (they would block too much)
        if (opts.contains("domain=") || opts.contains("~third-party") || opts.contains("1p") || opts.contains("first-party")) return
        if (exception) allow.add(domain) else block.add(domain)
    }

    /** Selectors that only work in uBlock (or would break the whole CSS rule) are dropped. */
    private fun safeSelector(s: String): Boolean =
        s.length < 400 && !s.contains(":-abp-") && !s.contains(":has-text") && !s.contains(":xpath") &&
            !s.contains(":upward") && !s.contains(":matches-") && !s.contains(":remove") && !s.contains(":style") &&
            !s.contains(":min-text") && !s.contains(":has(") && !s.contains(":watch-attr") && !s.contains(":others") && !s.contains("{") &&
            !s.startsWith("+js") && !s.startsWith("^")

    /** Many small rules, so one selector the browser doesn't understand only cancels its own group. */
    private fun buildCss(selectors: Collection<String>): String =
        selectors.chunked(150).joinToString("\n") { it.joinToString(",") + "{display:none!important}" }

    // ------------------------------------------------------------ checks used by the browser

    private fun matches(host: String, set: Set<String>): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
            if (!h.contains('.')) return false
        }
    }

    /** True if a request to [url] (made from a page on [pageHost]) is an ad / tracker. */
    fun shouldBlock(url: String, host: String?, pageHost: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        if (pageHost != null && SiteAllow.isAllowed(pageHost)) return false
        val h = host.lowercase(Locale.US)
        if (BuiltinAds.urlParts.any { url.contains(it) }) return true
        if (!matches(h, blockDomains)) return false
        return !matches(h, allowDomains)
    }

    /** A page (main frame) on an ad / pop-under domain — used to close pop-up tabs. */
    fun isAdPage(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase(Locale.US)
        return matches(h, blockDomains) && !matches(h, allowDomains)
    }

    /** [top] = the page itself; frames inside it only get the site-specific rules (keeps them light). */
    fun cssFor(host: String, top: Boolean = true): String {
        if (SiteAllow.isAllowed(host)) return ""
        val sb = StringBuilder(if (top) genericCss else "")
        var h = host.lowercase(Locale.US)
        val site = siteSelectors
        val own = ArrayList<String>()
        while (true) {
            site[h]?.let { own.addAll(it) }
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
        }
        if (h.endsWith("youtube.com") || host.contains("youtube.com")) own.addAll(BuiltinAds.YOUTUBE_SELECTORS)
        if (own.isNotEmpty()) sb.append('\n').append(buildCss(own))
        return sb.toString()
    }

    // ------------------------------------------------------------ page script

    const val BRIDGE = "AliAd"

    class Bridge {
        @JavascriptInterface fun css(host: String?, top: Boolean): String =
            if (!com.ali.files.util.AppSettings.adBlockFast || host.isNullOrEmpty()) "" else runCatching { cssFor(host, top) }.getOrDefault("")
        @JavascriptInterface fun on(host: String?): Boolean =
            com.ali.files.util.AppSettings.adBlockFast && (host.isNullOrEmpty() || !SiteAllow.isAllowed(host))
    }

    /** Runs at the start of every page and frame: hides ad boxes, and on YouTube skips video ads. */
    val SCRIPT = """
(function(){
  if (window.__aliAd) return; window.__aliAd = 1;
  var h = location.hostname;
  try {
    var css = $BRIDGE.css(h, window === window.top);
    if (css) {
      var add = function(){ var s = document.createElement('style'); s.textContent = css; (document.head || document.documentElement).appendChild(s); };
      if (document.documentElement) add(); else document.addEventListener('DOMContentLoaded', add);
    }
  } catch (e) {}
  if (!/(^|\.)youtube\.com$/.test(h)) return;
  try { if (!$BRIDGE.on(h)) return; } catch (e) { return; }
  var skipping = false;
  setInterval(function(){
    var v = document.querySelector('video');
    var ad = document.querySelector('.ad-showing, .ad-interrupting');
    if (ad && v) {
      if (!skipping) { skipping = true; v.__aliMuted = v.muted; }
      v.muted = true;
      try { v.playbackRate = 16; } catch (e) {}
      if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.2) v.currentTime = v.duration - 0.1;
    } else if (skipping && v) {
      skipping = false; v.muted = !!v.__aliMuted; v.playbackRate = 1;
    }
    var b = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytm-skip-ad-button, .ytp-ad-overlay-close-button');
    if (b) b.click();
  }, 250);
})();
"""
}

/** Sites where the user turned the blocker off (in case a site breaks or refuses to work with it). */
object SiteAllow {
    @Volatile private var sites: Set<String> = emptySet()
    private lateinit var sp: android.content.SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("adfilter", Context.MODE_PRIVATE)
        sites = sp.getStringSet("allowSites", emptySet())?.toSet() ?: emptySet()
    }

    private fun key(host: String) = host.lowercase(Locale.US).removePrefix("www.").removePrefix("m.")

    fun isAllowed(host: String): Boolean {
        if (sites.isEmpty()) return false
        var h = key(host)
        while (true) {
            if (h in sites) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
            if (!h.contains('.')) return false
        }
    }

    fun set(host: String, allowed: Boolean) {
        val k = key(host)
        sites = if (allowed) sites + k else sites - k
        sp.edit().putStringSet("allowSites", sites).apply()
    }
}
