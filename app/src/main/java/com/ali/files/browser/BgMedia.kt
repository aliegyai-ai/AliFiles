package com.ali.files.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ali.files.R
import com.ali.files.util.AppSettings
import com.ali.files.util.Notifications

/**
 * Videos in the browser keep playing when you leave the app (YouTube included), with controls in the
 * notification, on the lock screen and on headphones — and can shrink to a small window
 * (picture-in-picture) showing only the video.
 */
object BgMedia {
    const val BRIDGE = "AliMedia"

    /**
     * Runs in every page before its own code: the page always believes it's on screen (so it doesn't
     * pause when you leave the app), reports play/pause, and lets the app control the video.
     */
    val SCRIPT = """
(function(){
  if (window.__aliBg) return; window.__aliBg = 1;
  try {
    Object.defineProperty(Document.prototype, 'hidden', { get: function(){ return false; }, configurable: true });
    Object.defineProperty(Document.prototype, 'visibilityState', { get: function(){ return 'visible'; }, configurable: true });
    Object.defineProperty(Document.prototype, 'webkitHidden', { get: function(){ return false; }, configurable: true });
  } catch (e) {}
  ['visibilitychange', 'webkitvisibilitychange'].forEach(function(t){
    window.addEventListener(t, function(e){ e.stopImmediatePropagation(); }, true);
    document.addEventListener(t, function(e){ e.stopImmediatePropagation(); }, true);
  });
  // leaving the app / the small window takes the focus away: players pause on that too
  ['blur', 'pagehide', 'freeze'].forEach(function(t){
    window.addEventListener(t, function(e){ if (e.target === window || e.target === document) e.stopImmediatePropagation(); }, true);
  });
  try { Object.defineProperty(Document.prototype, 'hasFocus', { value: function(){ return true; }, configurable: true }); } catch (e) {}
  if (window.top !== window) return;
  function rep(v){ try { $BRIDGE.state(!v.paused && !v.ended, document.title || '', v.currentTime || 0, isFinite(v.duration) ? v.duration : 0); } catch (e) {} }
  document.addEventListener('play', function(e){ var t = e.target; if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) { window.__aliLast = t; rep(t); } }, true);
  ['pause', 'ended', 'seeked'].forEach(function(n){ document.addEventListener(n, function(e){ if (e.target === window.__aliLast) rep(e.target); }, true); });
  window.__aliCtl = function(c, x){
    var v = window.__aliLast || document.querySelector('video'); if (!v) return;
    if (c === 'toggle') { v.paused ? v.play() : v.pause(); }
    else if (c === 'play') v.play();
    else if (c === 'pause') v.pause();
    else if (c === 'seek') v.currentTime = Math.max(0, v.currentTime + x);
  };
  window.__aliPip = function(on){
    var v = window.__aliLast || document.querySelector('video'); if (!v) return;
    if (on) {
      v.__aliStyle = v.getAttribute('style') || '';
      v.setAttribute('style', v.__aliStyle + ';position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important');
      document.documentElement.style.overflow = 'hidden';
    } else {
      v.setAttribute('style', v.__aliStyle || '');
      document.documentElement.style.overflow = '';
    }
  };
})();
"""

    private val main = Handler(Looper.getMainLooper())
    @Volatile var tab: BrowserTab? = null
        private set
    var title = ""; var playing = false; var position = 0.0; var duration = 0.0
    private lateinit var app: Context

    class Bridge(private val c: Context, private val t: BrowserTab) {
        @JavascriptInterface
        fun state(isPlaying: Boolean, pageTitle: String, pos: Double, dur: Double) {
            main.post { update(c, t, isPlaying, pageTitle, pos, dur) }
        }
    }

    private fun update(c: Context, t: BrowserTab, isPlaying: Boolean, pageTitle: String, pos: Double, dur: Double) {
        app = c.applicationContext
        t.playing.value = isPlaying
        if (!isPlaying && tab !== t) return
        tab = t; playing = isPlaying; title = pageTitle.removeSuffix(" - YouTube"); position = pos; duration = dur
        if (!AppSettings.bgPlay.value) return
        if (isPlaying) ContextCompat.startForegroundService(app, Intent(app, BgMediaService::class.java))
        BgMediaService.instance?.refresh()
    }

    fun control(cmd: String, x: Int = 0) {
        val t = tab ?: return
        t.webView?.evaluateJavascript("window.__aliCtl && window.__aliCtl('$cmd', $x)", null)
    }

    fun stop() {
        control("pause")
        tab = null; playing = false
        BgMediaService.instance?.let { it.stopForeground(Service.STOP_FOREGROUND_REMOVE); it.stopSelf() }
    }

    /** a closed tab can't keep playing */
    fun tabClosed(t: BrowserTab) { if (tab === t) { tab = null; playing = false; BgMediaService.instance?.stopSelf() } }

    // ---------------------------------------------------------------- picture-in-picture

    fun pipEnter(c: BrowserController) {
        val t = c.current ?: return
        t.webView?.evaluateJavascript("window.__aliPip && window.__aliPip(true)", null)
        c.pipMode.value = true
    }

    fun pipExit(c: BrowserController) {
        if (!c.pipMode.value) return
        c.pipMode.value = false
        c.current?.webView?.evaluateJavascript("window.__aliPip && window.__aliPip(false)", null)
    }
}

/**
 * The browser's page view. When you leave the app Android tells it «you're not on screen», and the
 * video engine inside then pauses any playing video by itself. While a video plays (and «keep
 * playing» is on) it's told it's still on screen.
 */
class PageView(c: Context) : android.webkit.WebView(c) {
    var playing: () -> Boolean = { false }

    override fun onWindowVisibilityChanged(visibility: Int) {
        val keep = visibility != android.view.View.VISIBLE && AppSettings.bgPlay.value && playing()
        super.onWindowVisibilityChanged(if (keep) android.view.View.VISIBLE else visibility)
    }
}

/** Keeps the app alive while a browser video plays in the background, with its media notification. */
class BgMediaService : Service() {
    private lateinit var session: MediaSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "تشغيل المتصفح في الخلفية", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "ali-browser").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = BgMedia.control("play")
                override fun onPause() = BgMedia.control("pause")
                override fun onFastForward() = BgMedia.control("seek", 10)
                override fun onRewind() = BgMedia.control("seek", -10)
                override fun onSkipToNext() = BgMedia.control("seek", 10)
                override fun onSkipToPrevious() = BgMedia.control("seek", -10)
                override fun onStop() = BgMedia.stop()
            })
            isActive = true
        }
        ServiceCompat.startForeground(this, ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "toggle" -> BgMedia.control("toggle")
            "back" -> BgMedia.control("seek", -10)
            "fwd" -> BgMedia.control("seek", 10)
            "close" -> { BgMedia.stop(); return START_NOT_STICKY }
        }
        refresh()
        return START_NOT_STICKY
    }

    fun refresh() {
        if (BgMedia.tab == null) { stopSelf(); return }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, BgMedia.title.ifBlank { "فيديو من المتصفح" })
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (BgMedia.duration * 1000).toLong())
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP)
                .setState(if (BgMedia.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, (BgMedia.position * 1000).toLong(), if (BgMedia.playing) 1f else 0f)
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }

    private fun action(icon: Int, title: String, act: String, code: Int) = Notification.Action.Builder(
        android.graphics.drawable.Icon.createWithResource(this, icon), title,
        PendingIntent.getService(this, code, Intent(this, BgMediaService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
    ).build()

    private fun build(): Notification = Notification.Builder(this, CH)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(BgMedia.title.ifBlank { "فيديو من المتصفح" })
        .setContentText("شغال في الخلفية")
        .setContentIntent(Notifications.openAppIntent(this, 0))
        .setOngoing(BgMedia.playing)
        .addAction(action(android.R.drawable.ic_media_rew, "رجوع 10", "back", 1))
        .addAction(action(if (BgMedia.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "تشغيل", "toggle", 2))
        .addAction(action(android.R.drawable.ic_media_ff, "قدّام 10", "fwd", 3))
        .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "قفل", "close", 4))
        .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        .build()

    override fun onDestroy() {
        instance = null
        session.release()
        super.onDestroy()
    }

    companion object {
        private const val CH = "browser_media"
        private const val ID = 61
        @Volatile var instance: BgMediaService? = null
    }
}
