package com.ali.files.util

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

const val PUBLIC_FOLDER = "AliFiles"

fun formatBytes(b: Long): String {
    if (b < 0) return "؟"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "$b B" else String.format(Locale.US, "%.1f %s", v, units[i])
}

fun formatDuration(seconds: Long): String {
    if (seconds < 0) return "؟"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

fun mimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(name)) ?: "application/octet-stream"

fun sanitizeFileName(n: String): String =
    n.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(150).ifBlank { "file" }

enum class FileKind { IMAGE, VIDEO, AUDIO, DOC, ARCHIVE, APK, OTHER }

fun kindOf(mime: String?, name: String): FileKind {
    val m = (mime ?: "").lowercase(Locale.US)
    val e = extOf(name)
    return when {
        m.startsWith("image/") -> FileKind.IMAGE
        m.startsWith("video/") || e in setOf("mp4", "mkv", "webm", "ts", "mov", "3gp", "avi") -> FileKind.VIDEO
        m.startsWith("audio/") || e in setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus") -> FileKind.AUDIO
        e == "apk" || m == "application/vnd.android.package-archive" -> FileKind.APK
        e in setOf("zip", "rar", "7z", "tar", "gz") -> FileKind.ARCHIVE
        m.startsWith("text/") || e in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv") -> FileKind.DOC
        else -> FileKind.OTHER
    }
}

fun kindIcon(k: FileKind): ImageVector = when (k) {
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.VIDEO -> Icons.Filled.Movie
    FileKind.AUDIO -> Icons.Filled.AudioFile
    FileKind.DOC -> Icons.Filled.Description
    FileKind.ARCHIVE -> Icons.Filled.FolderZip
    FileKind.APK -> Icons.Filled.Android
    FileKind.OTHER -> Icons.Filled.InsertDriveFile
}

fun kindLabel(k: FileKind): String = when (k) {
    FileKind.IMAGE -> "صور"
    FileKind.VIDEO -> "فيديو"
    FileKind.AUDIO -> "صوت"
    FileKind.DOC -> "مستندات"
    FileKind.ARCHIVE -> "مضغوط"
    FileKind.APK -> "تطبيقات"
    FileKind.OTHER -> "أخرى"
}

object FileStore {
    /** Creates a file in Download/AliFiles through MediaStore and lets [write] fill it. */
    fun createInDownloads(c: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val resolver = c.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("تعذر إنشاء الملف في التنزيلات")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("تعذر الكتابة في التنزيلات")
            out.use { write(it) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    fun saveToDownloads(c: Context, src: File, name: String, mime: String): Uri =
        createInDownloads(c, name, mime) { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }

    fun displayName(c: Context, uri: Uri): String {
        runCatching {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val v = cur.getString(0)
                    if (!v.isNullOrBlank()) return v
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun open(c: Context, uri: Uri, mime: String?) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            c.startActivity(Intent.createChooser(intent, "فتح باستخدام").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(c, "مفيش تطبيق يفتح الملف ده", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(c: Context, uri: Uri, mime: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        c.startActivity(Intent.createChooser(intent, "مشاركة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Reads until [buf] is full or the stream ends. Returns the number of bytes read (0 at EOF). */
fun InputStream.readFully(buf: ByteArray): Int {
    var total = 0
    while (total < buf.size) {
        val n = read(buf, total, buf.size - total)
        if (n < 0) break
        total += n
    }
    return total
}
