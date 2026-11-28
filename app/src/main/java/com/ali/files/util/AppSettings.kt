package com.ali.files.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.net.URLEncoder

/** Persistent user settings, exposed as Compose state. */
object AppSettings {
    private lateinit var sp: SharedPreferences

    val threads = mutableIntStateOf(4)
    val adBlock = mutableStateOf(true)
    val searchEngine = mutableStateOf("google")
    val clipboardDetect = mutableStateOf(true)
    /** the big floating «download video» button over pages (off: use the toolbar icon) */
    val floatingDownload = mutableStateOf(false)
    /** small ⬇ buttons drawn on top of videos inside web pages (off: use the toolbar icon) */
    val inPageButtons = mutableStateOf(false)
    @Volatile var inPageButtonsFast = false
    /** make websites dark when the phone is in dark mode */
    val darkSites = mutableStateOf(true)
    /** browser videos keep playing when you leave the app / shrink to a small window */
    val bgPlay = mutableStateOf(true)
    val browserPip = mutableStateOf(true)
    /** what the app calls you ("" = not set yet); [nameAsked] = we already asked once */
    val userName = mutableStateOf("")
    val nameAsked = mutableStateOf(false)
    /** the last audio-only choice (remembered) */
    val audioQuality = mutableStateOf("HIGH")
    val audioFormat = mutableStateOf("mp3")
    /** updates: "auto" (download + install by itself), "download" (download, ask before installing), "ask" */
    val updateMode = mutableStateOf("auto")
    val updateWifiOnly = mutableStateOf(true)
    /** «حمّل بعدين» starts only on Wi-Fi */
    val scheduleWifiOnly = mutableStateOf(true)
    /** «وضع النسيان»: sites forget your visits (except [keepSites]) when you come back to the app */
    val forgetMode = mutableStateOf(false)
    val keepSites = mutableStateOf<Set<String>>(emptySet())
    /** library: put new downloads in a folder by "type", by "source" site, or "none" */
    val autoFolder = mutableStateOf("none")
    /** also copy finished downloads to the phone's gallery */
    val autoGallery = mutableStateOf(false)

    @Volatile var adBlockFast = true
        private set

    fun init(c: Context) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        threads.intValue = sp.getInt("threads", 4)
        adBlock.value = sp.getBoolean("adBlock", true)
        adBlockFast = adBlock.value
        searchEngine.value = sp.getString("searchEngine", "google") ?: "google"
        clipboardDetect.value = sp.getBoolean("clipboardDetect", true)
        floatingDownload.value = sp.getBoolean("floatingDownload", false)
        inPageButtons.value = sp.getBoolean("inPageButtons", false); inPageButtonsFast = inPageButtons.value
        darkSites.value = sp.getBoolean("darkSites", true)
        bgPlay.value = sp.getBoolean("bgPlay", true)
        browserPip.value = sp.getBoolean("browserPip", true)
        userName.value = sp.getString("userName", "") ?: ""
        nameAsked.value = sp.getBoolean("nameAsked", false)
        audioQuality.value = sp.getString("audioQuality", "HIGH") ?: "HIGH"
        audioFormat.value = sp.getString("audioFormat", "mp3") ?: "mp3"
        updateMode.value = sp.getString("updateMode", "auto") ?: "auto"
        updateWifiOnly.value = sp.getBoolean("updateWifiOnly", true)
        scheduleWifiOnly.value = sp.getBoolean("scheduleWifiOnly", true)
        forgetMode.value = sp.getBoolean("forgetMode", false)
        keepSites.value = sp.getStringSet("keepSites", emptySet())?.toSet() ?: emptySet()
        autoFolder.value = sp.getString("autoFolder", "none") ?: "none"
        autoGallery.value = sp.getBoolean("autoGallery", false)
    }

    fun setThreads(v: Int) { threads.intValue = v; sp.edit().putInt("threads", v).apply() }
    fun setAdBlock(v: Boolean) { adBlock.value = v; adBlockFast = v; sp.edit().putBoolean("adBlock", v).apply() }
    fun setSearchEngine(v: String) { searchEngine.value = v; sp.edit().putString("searchEngine", v).apply() }
    fun setClipboardDetect(v: Boolean) { clipboardDetect.value = v; sp.edit().putBoolean("clipboardDetect", v).apply() }
    fun setInPageButtons(v: Boolean) { inPageButtons.value = v; inPageButtonsFast = v; sp.edit().putBoolean("inPageButtons", v).apply() }
    fun setAutoFolder(v: String) { autoFolder.value = v; sp.edit().putString("autoFolder", v).apply() }
    fun setAutoGallery(v: Boolean) { autoGallery.value = v; sp.edit().putBoolean("autoGallery", v).apply() }
    fun setUpdateMode(v: String) { updateMode.value = v; sp.edit().putString("updateMode", v).apply() }
    fun setScheduleWifiOnly(v: Boolean) { scheduleWifiOnly.value = v; sp.edit().putBoolean("scheduleWifiOnly", v).apply() }
    fun setUpdateWifiOnly(v: Boolean) { updateWifiOnly.value = v; sp.edit().putBoolean("updateWifiOnly", v).apply() }
    fun setForgetMode(v: Boolean) { forgetMode.value = v; sp.edit().putBoolean("forgetMode", v).apply() }
    fun setKeepSites(v: Set<String>) { keepSites.value = v; sp.edit().putStringSet("keepSites", v).apply() }
    fun setAudioQuality(v: String) { audioQuality.value = v; sp.edit().putString("audioQuality", v).apply() }
    fun setAudioFormat(v: String) { audioFormat.value = v; sp.edit().putString("audioFormat", v).apply() }
    fun setUserName(v: String) {
        userName.value = v.trim(); nameAsked.value = true
        sp.edit().putString("userName", v.trim()).putBoolean("nameAsked", true).apply()
    }
    fun setBgPlay(v: Boolean) { bgPlay.value = v; sp.edit().putBoolean("bgPlay", v).apply() }
    fun setBrowserPip(v: Boolean) { browserPip.value = v; sp.edit().putBoolean("browserPip", v).apply() }
    fun setDarkSites(v: Boolean) { darkSites.value = v; sp.edit().putBoolean("darkSites", v).apply() }
    fun setFloatingDownload(v: Boolean) { floatingDownload.value = v; sp.edit().putBoolean("floatingDownload", v).apply() }

    var lastClip: String?
        get() = sp.getString("lastClip", null)
        set(v) { sp.edit().putString("lastClip", v).apply() }

    fun homeUrl(): String = when (searchEngine.value) {
        "ddg" -> "https://duckduckgo.com/"
        "bing" -> "https://www.bing.com/"
        else -> "https://www.google.com/"
    }

    fun searchUrl(q: String): String {
        val base = when (searchEngine.value) {
            "brave" -> "https://search.brave.com/search?q="
            "ddg" -> "https://duckduckgo.com/?q="
            "bing" -> "https://www.bing.com/search?q="
            else -> "https://www.google.com/search?q="
        }
        return base + URLEncoder.encode(q, "UTF-8")
    }
}
