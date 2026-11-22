package com.ali.files.browser

import android.webkit.WebViewClient

/** The app's own page when a site can't be opened (no internet, site down…), instead of Android's grey one. */
object ErrorPage {
    /** only real «couldn't reach it» errors — not cancelled loads or downloads */
    val codes = setOf(
        WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT,
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, WebViewClient.ERROR_PROXY_AUTHENTICATION, WebViewClient.ERROR_BAD_URL,
    )

    /** [insecure]: the original http address, when a private tab upgraded it to https and that failed */
    fun html(code: Int, url: String, insecure: String? = null): String {
        val (icon, title, text) = when (code) {
            WebViewClient.ERROR_HOST_LOOKUP -> Triple("📡", "مفيش نت أو الموقع مش موجود", "اتأكد إن الواي فاي أو البيانات شغّالة، وإن العنوان مكتوب صح.")
            WebViewClient.ERROR_TIMEOUT -> Triple("⏳", "الموقع اتأخر في الرد", "النت ممكن يكون ضعيف، أو الموقع عليه ضغط. جرّب تاني.")
            WebViewClient.ERROR_CONNECT -> Triple("🔌", "مقدرتش أوصل للموقع", "الموقع ممكن يكون واقع أو مقفول على الشبكة دي.")
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> Triple("🔒", "مشكلة في أمان الاتصال", "الموقع مش قادر يعمل اتصال آمن. ممكن تكون ساعة الموبايل غلط.")
            else -> Triple("⚠️", "الصفحة مفتحتش", "حصلت مشكلة وانا بفتح الصفحة.")
        }
        val extra = if (insecure == null) "" else
            "<p style='margin-top:22px;font-size:13px'>الموقع ده ممكن ميدعمش الاتصال المشفّر.</p>" +
                "<a style='color:#d4a94f;font-size:14px' href='alifiles-insecure:${android.net.Uri.encode(insecure)}'>افتحه من غير تشفير (مش آمن)</a>"
        val safeUrl = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        val jsUrl = url.replace("\\", "\\\\").replace("'", "\\'")
        return """<!doctype html><html dir="rtl" lang="ar"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
<style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0e0d0b;color:#f3ede0;
font-family:sans-serif;text-align:center}
@media (prefers-color-scheme: light){body{background:#f8f4ec;color:#221d14}.u{color:#6e6452}}
.box{padding:32px;max-width:420px}.i{font-size:56px}h1{font-size:21px;margin:16px 0 8px}p{opacity:.8;line-height:1.7;margin:0 0 6px}
.u{color:#a39a88;font-size:12px;direction:ltr;word-break:break-all;margin:12px 0 24px}
button{background:#d4a94f;color:#1a1408;border:0;border-radius:14px;padding:13px 30px;font-size:16px;font-weight:bold}
</style></head><body><div class="box"><div class="i">$icon</div><h1>$title</h1><p>$text</p>
<div class="u">$safeUrl</div><button onclick="location.href='$jsUrl'">جرّب تاني</button>$extra</div></body></html>"""
    }
}
