package com.ali.files.browser

/** Rules that work even before the big lists are loaded, plus things the lists don't cover (YouTube). */
object BuiltinAds {
    val hosts: Set<String> = setOf(
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

    /** Ad requests that live on normal domains (so domain blocking can't catch them). */
    val urlParts = listOf(
        "youtube.com/pagead/", "youtube.com/api/stats/ads", "youtube.com/ptracking", "youtube.com/get_midroll_",
        "/youtubei/v1/player/ad_break", "facebook.com/tr?", "/ads/ga-audiences",
    )

    val GENERIC_SELECTORS = listOf(
        "ins.adsbygoogle", "[id^='google_ads_']", "[id^='div-gpt-ad']", "iframe[src*='doubleclick.net']",
        "iframe[src*='googlesyndication']", "[class*='adsbygoogle']", "[data-ad-slot]", "[data-google-query-id]",
        ".taboola", "[id^='taboola-']", ".OUTBRAIN", "[data-widget-id^='outbrain']", "amp-ad", "amp-embed[type='taboola']",
    )

    val GENERIC_CSS: String = GENERIC_SELECTORS.joinToString(",") + "{display:none!important}"

    val YOUTUBE_SELECTORS = listOf(
        "ytm-promoted-sparkles-web-renderer", "ytm-promoted-video-renderer", "ytm-companion-ad-renderer",
        "ad-slot-renderer", "ytm-ad-slot-renderer", "ytd-ad-slot-renderer", "#player-ads", "#masthead-ad",
        "ytd-promoted-sparkles-web-renderer", "ytd-promoted-video-renderer", "ytd-banner-promo-renderer",
        "ytm-mealbar-promo-renderer", "ytm-statement-banner-renderer", ".ytp-ad-overlay-container",
        ".ytp-ad-module .ytp-ad-image-overlay", "ytd-display-ad-renderer", "ytd-in-feed-ad-layout-renderer",
        "ytm-promoted-item-renderer", "ytd-engagement-panel-section-list-renderer[target-id='engagement-panel-ads']",
    )
}
