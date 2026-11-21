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
    }

    fun setThreads(v: Int) { threads.intValue = v; sp.edit().putInt("threads", v).apply() }
    fun setSaveToVault(v: Boolean) { saveToVault.value = v; sp.edit().putBoolean("saveToVault", v).apply() }
    fun setAdBlock(v: Boolean) { adBlock.value = v; adBlockFast = v; sp.edit().putBoolean("adBlock", v).apply() }
    fun setSearchEngine(v: String) { searchEngine.value = v; sp.edit().putString("searchEngine", v).apply() }
    fun setClipboardDetect(v: Boolean) { clipboardDetect.value = v; sp.edit().putBoolean("clipboardDetect", v).apply() }
    fun setBiometric(v: Boolean) { biometric.value = v; sp.edit().putBoolean("biometric", v).apply() }

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
            "ddg" -> "https://duckduckgo.com/?q="
            "bing" -> "https://www.bing.com/search?q="
            else -> "https://www.google.com/search?q="
        }
        return base + URLEncoder.encode(q, "UTF-8")
    }
}
