package com.ali.files.browser

import android.webkit.JavascriptInterface

/**
 * Tells the app when a finger lands on a list that scrolls on its own inside a page (YouTube's
 * playlist panel, comments, menus). Pull-to-refresh then stays out of the way for that gesture.
 */
object InnerScroll {
    const val BRIDGE = "AliScroll"

    class Bridge(private val tab: BrowserTab) {
        @JavascriptInterface
        fun inner(on: Boolean) { tab.touchInInnerList = on }
    }

    val SCRIPT = """
(function(){
  if (window.__aliScroll) return; window.__aliScroll = 1;
  function scroller(el){
    while (el && el !== document.body && el !== document.documentElement) {
      if (el.scrollHeight > el.clientHeight + 2) {
        var o = getComputedStyle(el).overflowY;
        if (o === 'auto' || o === 'scroll' || o === 'overlay') return el;
      }
      el = el.parentElement;
    }
    return null;
  }
  document.addEventListener('touchstart', function(e){
    var s = null;
    try { s = scroller(e.target); } catch (x) {}
    // inside an iframe: the whole frame is "inside the page"
    try { $BRIDGE.inner(!!s || window.top !== window); } catch (x) {}
  }, { capture: true, passive: true });
  document.addEventListener('touchend', function(){ try { $BRIDGE.inner(false); } catch (x) {} }, { capture: true, passive: true });
})();
"""
}
