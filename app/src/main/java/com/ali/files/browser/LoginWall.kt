package com.ali.files.browser

import android.webkit.JavascriptInterface

/**
 * «من غير تسجيل»: Facebook, Instagram, TikTok, X… cover the page with a «log in» window and lock the
 * scroll, though the video is usually right under it. This removes that window, gives the scroll back,
 * and tells the app when one showed up (so it can offer «جرّب من غير تسجيل»).
 * What a site really keeps from people who aren't logged in can't be shown — nothing here pretends otherwise.
 */
object LoginWall {
    const val BRIDGE = "AliWall"

    class Bridge(private val tab: BrowserTab) {
        @JavascriptInterface
        fun wall(on: Boolean) { tab.loginWall.value = on }
    }

    private val HOSTS = listOf("facebook.com", "instagram.com", "tiktok.com", "twitter.com", "x.com", "linkedin.com", "pinterest.", "reddit.com", "threads.net")

    fun isWallSite(url: String): Boolean {
        val h = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return HOSTS.any { h.contains(it) }
    }

    /** Instagram: the embed page of a public post / reel plays without an account */
    fun embedUrl(url: String): String? {
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (!host.contains("instagram.com")) return null
        val seg = u.pathSegments
        val i = seg.indexOfFirst { it == "p" || it == "reel" || it == "reels" || it == "tv" }
        if (i < 0 || i + 1 >= seg.size) return null
        val kind = if (seg[i] == "reels") "reel" else seg[i]
        return "https://www.instagram.com/$kind/${seg[i + 1]}/embed/"
    }

    /** Facebook: the lighter mobile site asks for far less */
    fun lightUrl(url: String): String? {
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (!host.contains("facebook.com") || host.startsWith("m.") || host.startsWith("mbasic.")) return null
        return u.buildUpon().authority("m.facebook.com").build().toString()
    }

    val SCRIPT = """
(function(){
  if (window.__aliWall) { try { window.__aliWall(); } catch(x){} return; }
  var host = location.hostname;
  if (!/facebook|instagram|tiktok|twitter|x\.com|linkedin|pinterest|reddit|threads/.test(host)) return;
  var RE = /(log ?in|sign ?in|sign ?up|log into|create (an )?account|join (us|facebook|instagram)|see more on|تسجيل الدخول|سجّ?ل (الدخول|حساب)|انضم|إنشاء حساب|دخول)/i;
  var NOTNOW = /(not now|maybe later|close|skip|no thanks|مش دلوقتي|ليس الآن|لاحقًا|لاحقا|إغلاق|اغلاق|تخطي)/i;
  var seen = false;
  function covers(el){
    var r = el.getBoundingClientRect();
    return r.width > innerWidth * 0.55 && r.height > Math.min(innerHeight * 0.35, 260);
  }
  function wallLike(el){
    var cs = getComputedStyle(el);
    if (cs.position !== 'fixed' && cs.position !== 'sticky' && cs.position !== 'absolute') return false;
    if (!covers(el)) return false;
    var z = parseInt(cs.zIndex) || 0;
    var dlg = el.getAttribute('role') === 'dialog' || el.getAttribute('aria-modal') === 'true';
    return (dlg || z >= 10) && RE.test((el.innerText || '').slice(0, 600));
  }
  function clean(){
    var found = false;
    try {
      var all = document.querySelectorAll('div,section,aside,[role=dialog]');
      for (var i = 0; i < all.length && i < 1500; i++) {
        var el = all[i];
        if (!wallLike(el)) continue;
        // a «not now» button first (the site's own way out), otherwise the window goes
        var btns = el.querySelectorAll('button,[role=button],a,div[tabindex]');
        var done = false;
        for (var j = 0; j < btns.length; j++) {
          if (NOTNOW.test((btns[j].innerText || btns[j].getAttribute('aria-label') || '').trim().slice(0, 30))) { try { btns[j].click(); done = true; } catch(x){} break; }
        }
        el.style.setProperty('display', 'none', 'important');
        found = true;
      }
      // the grey curtain behind such a window, and the locked scroll
      var d = document.documentElement, b = document.body;
      if (found || seen) {
        [d, b].forEach(function(n){ if (n) { n.style.setProperty('overflow', 'auto', 'important'); n.style.setProperty('position', 'static', 'important'); n.style.setProperty('pointer-events', 'auto', 'important'); } });
      }
      // a blurred / dimmed video behind it
      if (found) document.querySelectorAll('video').forEach(function(v){ v.style.setProperty('filter', 'none', 'important'); v.controls = true; });
      // bottom «log in to continue» bars
      var bars = document.querySelectorAll('div[role=banner],div');
      for (var k = 0; k < bars.length && k < 800; k++) {
        var e = bars[k], c = getComputedStyle(e);
        if (c.position === 'fixed' && parseInt(c.bottom) <= 4 && e.getBoundingClientRect().width > innerWidth * 0.8 && e.getBoundingClientRect().height < 220 && RE.test((e.innerText || '').slice(0, 200)) && !e.querySelector('input')) {
          e.style.setProperty('display', 'none', 'important'); found = true;
        }
      }
    } catch(x) {}
    if (found) seen = true;
    try { $BRIDGE.wall(found || seen); } catch(x) {}
  }
  window.__aliWall = clean;
  var t = null;
  new MutationObserver(function(){ if (t) return; t = setTimeout(function(){ t = null; clean(); }, 250); })
    .observe(document.documentElement, { childList: true, subtree: true });
  setTimeout(clean, 800); setTimeout(clean, 2500);
})();
"""
}
