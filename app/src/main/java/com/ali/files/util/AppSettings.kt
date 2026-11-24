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
    val saveToVault = mutableStateOf(false)
    val adBlock = mutableStateOf(true)
    val searchEngine = mutableStateOf("google")
    val clipboardDetect = mutableStateOf(true)
    val biometric = mutableStateOf(true)
    /** the big floating «download video» button over pages (off: use the toolbar icon) */
    val floatingDownload = mutableStateOf(false)
    /** ask for the fingerprint before opening the private browser */
    val privateLock = mutableStateOf(false)
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
    /** «وضع النسيان»: sites forget your visits (except [keepSites]) when you come back to the app */
    val forgetMode = mutableStateOf(false)
    val keepSites = mutableStateOf<Set<String>>(emptySet())
    /** library: put new downloads in a folder by "type", by "source" site, or "none" */
    val autoFolder = mutableStateOf("none")
    /** also copy finished downloads to the phone's gallery */
    val autoGallery = mutableStateOf(false)
    /** torrents: only on Wi-Fi / keep sharing for a while after finishing / download limit in KB/s (0 = none) */
    val torrentWifiOnly = mutableStateOf(false)
    val torrentSeed = mutableStateOf(false)
    val torrentLimitKb = mutableStateOf(0)
    /** private tabs: search engine ("ddg", "brave", "same" = like normal tabs) */
    val privateSearch = mutableStateOf("ddg")
    /** private tabs wipe themselves: -1 never, 0 as soon as you leave the app, else minutes after leaving */
    val privateAutoWipe = mutableStateOf(-1)

    @Volatile var adBlockFast = true
        private set

    fun init(c: Context) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        threads.intValue = sp.getInt("threads", 4)
        saveToVault.value = sp.getBoolean("saveToVault", false)
        adBlock.value = sp.getBoolean("adBlock", true)
        adBlockFast = adBlock.value
        searchEngine.value = sp.getString("searchEngine", "google") ?: "google"
        clipboardDetect.value = sp.getBoolean("clipboardDetect", true)
        biometric.value = sp.getBoolean("biometric", true)
        floatingDownload.value = sp.getBoolean("floatingDownload", false)
        privateLock.value = sp.getBoolean("privateLock", false)
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
        forgetMode.value = sp.getBoolean("forgetMode", false)
        keepSites.value = sp.getStringSet("keepSites", emptySet())?.toSet() ?: emptySet()
        autoFolder.value = sp.getString("autoFolder", "none") ?: "none"
        autoGallery.value = sp.getBoolean("autoGallery", false)
        torrentWifiOnly.value = sp.getBoolean("torrentWifiOnly", false)
        torrentSeed.value = sp.getBoolean("torrentSeed", false)
        torrentLimitKb.value = sp.getInt("torrentLimitKb", 0)
        privateSearch.value = sp.getString("privateSearch", "ddg") ?: "ddg"
        privateAutoWipe.value = sp.getInt("privateAutoWipe", -1)
    }

    fun setThreads(v: Int) { threads.intValue = v; sp.edit().putInt("threads", v).apply() }
    fun setSaveToVault(v: Boolean) { saveToVault.value = v; sp.edit().putBoolean("saveToVault", v).apply() }
    fun setAdBlock(v: Boolean) { adBlock.value = v; adBlockFast = v; sp.edit().putBoolean("adBlock", v).apply() }
    fun setSearchEngine(v: String) { searchEngine.value = v; sp.edit().putString("searchEngine", v).apply() }
    fun setClipboardDetect(v: Boolean) { clipboardDetect.value = v; sp.edit().putBoolean("clipboardDetect", v).apply() }
    fun setInPageButtons(v: Boolean) { inPageButtons.value = v; inPageButtonsFast = v; sp.edit().putBoolean("inPageButtons", v).apply() }
    fun setPrivateSearch(v: String) { privateSearch.value = v; sp.edit().putString("privateSearch", v).apply() }
    fun setPrivateAutoWipe(v: Int) { privateAutoWipe.value = v; sp.edit().putInt("privateAutoWipe", v).apply() }
    fun setTorrentWifiOnly(v: Boolean) { torrentWifiOnly.value = v; sp.edit().putBoolean("torrentWifiOnly", v).apply() }
    fun setTorrentSeed(v: Boolean) { torrentSeed.value = v; sp.edit().putBoolean("torrentSeed", v).apply() }
    fun setTorrentLimitKb(v: Int) { torrentLimitKb.value = v; sp.edit().putInt("torrentLimitKb", v).apply() }
    fun setAutoFolder(v: String) { autoFolder.value = v; sp.edit().putString("autoFolder", v).apply() }
    fun setAutoGallery(v: Boolean) { autoGallery.value = v; sp.edit().putBoolean("autoGallery", v).apply() }
    fun setUpdateMode(v: String) { updateMode.value = v; sp.edit().putString("updateMode", v).apply() }
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
    fun setPrivateLock(v: Boolean) { privateLock.value = v; sp.edit().putBoolean("privateLock", v).apply() }
    fun setFloatingDownload(v: Boolean) { floatingDownload.value = v; sp.edit().putBoolean("floatingDownload", v).apply() }
    fun setBiometric(v: Boolean) { biometric.value = v; sp.edit().putBoolean("biometric", v).apply() }

    var lastClip: String?
        get() = sp.getString("lastClip", null)
        set(v) { sp.edit().putString("lastClip", v).apply() }

    fun homeUrl(): String = when (searchEngine.value) {
        "ddg" -> "https://duckduckgo.com/"
        "bing" -> "https://www.bing.com/"
        else -> "https://www.google.com/"
    }

    fun searchUrl(q: String, private: Boolean = false): String {
        val engine = if (private && privateSearch.value != "same") privateSearch.value else searchEngine.value
        val base = when (engine) {
            "brave" -> "https://search.brave.com/search?q="
            "ddg" -> "https://duckduckgo.com/?q="
            "bing" -> "https://www.bing.com/search?q="
            else -> "https://www.google.com/search?q="
        }
        return base + URLEncoder.encode(q, "UTF-8")
    }
}
