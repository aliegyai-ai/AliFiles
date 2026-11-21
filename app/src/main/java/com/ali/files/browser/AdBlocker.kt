package com.ali.files.browser

import java.util.Locale

/** Blocks requests to well-known ad / tracking / pop-under hosts (and their sub-domains). */
object AdBlocker {
    private val hosts: Set<String> = setOf(
        // ad networks
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "googletagservices.com", "adnxs.com", "adsrvr.org",
        "amazon-adsystem.com", "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com",
        "openx.net", "casalemedia.com", "indexww.com", "smartadserver.com", "yieldmo.com",
        "taboola.com", "outbrain.com", "revcontent.com", "mgid.com", "zergnet.com",
        "adcolony.com", "applovin.com", "unityads.unity3d.com", "inmobi.com", "vungle.com",
        "chartboost.com", "startappservice.com", "admob.com", "media.net", "advertising.com",
        "bidswitch.net", "sharethrough.com", "teads.tv", "33across.com", "contextweb.com",
        "adform.net", "spotxchange.com", "springserve.com", "lijit.com", "sovrn.com",
        // pop-under / aggressive networks common on download & streaming sites
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "adsterra.com",
        "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net", "hilltopads.net",
        "adcash.com", "clickadu.com", "onclickads.net", "onclkds.com", "popmyads.com",
        "admaven.com", "ad-maven.com", "galaksion.com", "monetag.com", "richpush.co",
        "pushwhy.com", "evadav.com", "a-ads.com", "bitmedia.io", "coinzilla.com",
        "highperformanceformat.com", "profitablecpmrate.com", "effectivecpmrate.com",
        "realsrv.com", "tsyndicate.com", "magsrv.com", "adskeeper.com", "adspyglass.com",
        // trackers
        "google-analytics.com", "scorecardresearch.com", "quantserve.com", "moatads.com",
        "hotjar.com", "mixpanel.com", "chartbeat.com", "krxd.net", "bluekai.com",
        "demdex.net", "everesttech.net", "rlcdn.com", "adsymptotic.com", "omtrdc.net",
        "zedo.com", "yieldlab.net", "serving-sys.com", "flashtalking.com",
    )

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase(Locale.US)
        while (true) {
            if (h in hosts) return true
            val i = h.indexOf('.')
            if (i < 0 || i == h.length - 1) return false
            h = h.substring(i + 1)
            if (!h.contains('.')) return false
        }
    }
}

/** Recognises media URLs while pages load. */
object MediaSniffer {
    private val video = setOf("mp4", "webm", "mkv", "mov", "3gp", "flv", "avi")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

    fun kind(url: String): String? {
        if (!url.startsWith("http")) return null
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return when {
            ext == "m3u8" -> "HLS"
            ext in video -> "فيديو"
            ext in audio -> "صوت"
            else -> null
        }
    }

    const val DOM_SCRIPT =
        "(function(){var s={};document.querySelectorAll('video,audio,source').forEach(function(e){" +
            "var u=e.currentSrc||e.src;if(u&&u.indexOf('http')===0)s[u]=1});return Object.keys(s);})()"
}
