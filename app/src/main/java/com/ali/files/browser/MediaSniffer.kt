package com.ali.files.browser

import java.util.Locale

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
