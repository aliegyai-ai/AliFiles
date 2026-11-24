package com.ali.files.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings

/**
 * Keeps downloads going with the screen off or while using another app: the phone's processor and
 * Wi-Fi stay awake while something downloads (released as soon as it's done).
 */
class KeepAwake(c: Context, tag: String) {
    private val wake = (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AliFiles:$tag").apply { setReferenceCounted(false) }
    @Suppress("DEPRECATION")
    private val wifi = (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AliFiles:$tag").apply { setReferenceCounted(false) }

    @SuppressLint("WakelockTimeout")
    fun on() { runCatching { if (!wake.isHeld) wake.acquire() }; runCatching { if (!wifi.isHeld) wifi.acquire() } }
    fun off() { runCatching { if (wake.isHeld) wake.release() }; runCatching { if (wifi.isHeld) wifi.release() } }

    companion object {
        /** Battery saver kills background downloads on many phones (Samsung especially): ask once to be left alone. */
        @SuppressLint("BatteryLife")
        fun askBatteryExemption(a: Activity) {
            val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(a.packageName)) return
            val sp = a.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (sp.getBoolean("askedBattery", false)) return
            sp.edit().putBoolean("askedBattery", true).apply()
            runCatching {
                a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.packageName)))
            }
        }
    }
}
