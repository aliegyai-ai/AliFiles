package com.ali.files.browser

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.ali.files.util.AppSettings

/**
 * «وضع النسيان»: sites forget what you watched and searched — their cookies and saved data are
 * removed — except the sites you asked to be remembered (so you stay signed in there).
 */
object ForgetSites {
    private val twoPart = setOf("com", "co", "org", "net", "gov", "edu", "ac")

    /** youtube.com for m.youtube.com, google.com.eg for www.google.com.eg */
    fun siteOf(host: String): String {
        val p = host.lowercase().removePrefix("www.").split('.')
        if (p.size <= 2) return p.joinToString(".")
        return if (p[p.size - 1].length == 2 && p[p.size - 2] in twoPart) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
    }

    fun isKept(host: String) = siteOf(host) in AppSettings.keepSites.value

    fun toggleKeep(host: String) {
        val s = siteOf(host)
        AppSettings.setKeepSites(if (s in AppSettings.keepSites.value) AppSettings.keepSites.value - s else AppSettings.keepSites.value + s)
    }

    /** Removes one site's cookies and saved data (normal tabs). */
    fun forget(host: String) {
        val site = siteOf(host)
        val cm = CookieManager.getInstance()
        for (h in listOf(site, "www.$site", "m.$site", host).distinct()) {
            val url = "https://$h"
            val names = cm.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() } ?: continue
            for (n in names) {
                cm.setCookie(url, "$n=; Max-Age=0; Path=/")
                cm.setCookie(url, "$n=; Max-Age=0; Path=/; Domain=.$site")
                cm.setCookie(url, "$n=; Max-Age=0; Path=/; Domain=$h")
            }
            WebStorage.getInstance().deleteOrigin("https://$h")
            WebStorage.getInstance().deleteOrigin("http://$h")
        }
        cm.flush()
    }

    /** Every site you visited forgets you, except the kept ones. */
    fun forgetAll(c: BrowserController) {
        val hosts = HashSet<String>()
        c.history.forEach { e -> runCatching { Uri.parse(e.url).host }.getOrNull()?.let { hosts += it } }
        c.tabs.filter { !it.incognito }.forEach { t -> runCatching { Uri.parse(t.url.value).host }.getOrNull()?.let { hosts += it } }
        WebStorage.getInstance().getOrigins { map ->
            val all = HashSet(hosts)
            (map as? Map<*, *>)?.keys?.forEach { k -> runCatching { Uri.parse(k.toString()).host }.getOrNull()?.let { all += it } }
            all.filter { !isKept(it) }.forEach { runCatching { forget(it) } }
        }
    }
}
