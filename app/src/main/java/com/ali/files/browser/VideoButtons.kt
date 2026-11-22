package com.ali.files.browser

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Puts a small ⬇ button on the top corner of every visible <video> in a page (and in its
 * iframes), and tells the app which video is playing so a floating button can download it.
 */
object VideoButtons {
    const val BRIDGE = "AliDl"

    val SCRIPT = """
(function(){
  if (window.__aliDl) return; window.__aliDl = 1;
  var btns = [];
  function mk(v){
    var b = document.createElement('div');
    b.textContent = '⬇';
    b.setAttribute('aria-label','تحميل الفيديو');
    var s = b.style;
    s.position='fixed'; s.zIndex='2147483647'; s.width='40px'; s.height='40px'; s.borderRadius='20px';
    s.background='rgba(212,169,79,0.95)'; s.color='#1a1408'; s.font='bold 22px sans-serif'; s.lineHeight='40px';
    s.textAlign='center'; s.boxShadow='0 2px 6px rgba(0,0,0,.45)'; s.cursor='pointer'; s.display='none';
    s.userSelect='none'; s.webkitUserSelect='none';
    var fire = function(e){
      e.preventDefault(); e.stopPropagation();
      try { $BRIDGE.download(v.currentSrc || v.src || '', location.href, document.title || ''); } catch(x) {}
    };
    b.addEventListener('click', fire, true);
    b.addEventListener('touchend', fire, true);
    (document.body || document.documentElement).appendChild(b);
    v.addEventListener('playing', function(){
      try { $BRIDGE.playing(v.currentSrc || v.src || '', location.href); } catch(x) {}
    });
    return {v:v, b:b};
  }
  function tick(){
    if (!document.documentElement) return;
    var vids = document.querySelectorAll('video');
    for (var i=0;i<vids.length;i++){
      var v = vids[i], found = false;
      for (var j=0;j<btns.length;j++) if (btns[j].v === v) { found = true; break; }
      if (!found) btns.push(mk(v));
    }
    for (var k=btns.length-1;k>=0;k--){
      var e = btns[k];
      if (!e.v.isConnected) { if (e.b.parentNode) e.b.parentNode.removeChild(e.b); btns.splice(k,1); continue; }
      var on = false; try { on = $BRIDGE.buttons(); } catch(x) {}
      if (!on) { e.b.style.display='none'; continue; }
      var r = e.v.getBoundingClientRect();
      var vis = r.width >= 120 && r.height >= 70 && r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
      if (!vis) { e.b.style.display='none'; continue; }
      if (!e.b.isConnected) (document.body || document.documentElement).appendChild(e.b);
      e.b.style.display='block';
      e.b.style.top = Math.max(4, r.top + 8) + 'px';
      e.b.style.left = Math.min(innerWidth - 48, r.right - 48) + 'px';
    }
  }
  setInterval(tick, 700);
  addEventListener('scroll', tick, true);
  addEventListener('resize', tick);
})();
""".trimIndent()

    /** Exposed to page JavaScript as window.AliDl. Calls arrive on a background thread. */
    class Bridge(private val onDownload: (src: String, page: String) -> Unit, private val onPlaying: (src: String, page: String) -> Unit) {
        private val main = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun download(src: String?, page: String?, title: String?) {
            val s = src ?: ""
            val p = page ?: ""
            main.post { onDownload(s, p) }
        }

        /** whether to draw the ⬇ buttons (Settings) */
        @JavascriptInterface
        fun buttons(): Boolean = com.ali.files.util.AppSettings.inPageButtonsFast

        @JavascriptInterface
        fun playing(src: String?, page: String?) {
            val s = src ?: ""
            val p = page ?: ""
            main.post { onPlaying(s, p) }
        }
    }
}
