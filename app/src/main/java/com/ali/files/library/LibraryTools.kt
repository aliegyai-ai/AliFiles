package com.ali.files.library

import android.content.Context
import android.net.Uri
import com.ali.files.player.PlayerHub
import com.ali.files.util.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** duplicates, storage cleanup and backup of the library's organisation (playlists, categories, favorites, resume spots) */
object LibraryTools {
    private const val LIB = "@LIB@"

    // ------------------------------------------------------------ duplicates

    /** a cheap fingerprint: size + the start, middle and end of the file */
    private fun fingerprint(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        val len = f.length()
        RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(64 * 1024)
            for (off in longArrayOf(0, (len / 2 - 32 * 1024).coerceAtLeast(0), (len - 64 * 1024).coerceAtLeast(0))) {
                r.seek(off)
                val n = r.read(buf)
                if (n > 0) md.update(buf, 0, n)
            }
        }
        return "$len:" + md.digest().joinToString("") { "%02x".format(it) }
    }

    /** groups of files that are the same file saved twice (the oldest is first) */
    suspend fun duplicates(): List<List<LibFile>> = withContext(Dispatchers.IO) {
        Library.files.value.filter { it.size > 50 * 1024 }
            .groupBy { it.size }.values.filter { it.size > 1 }
            .flatMap { sameSize -> sameSize.groupBy { runCatching { fingerprint(it.file) }.getOrDefault(it.path) }.values }
            .filter { it.size > 1 }
            .map { g -> g.sortedBy { it.modified } }
            .sortedByDescending { g -> g.first().size * (g.size - 1) }
    }

    // ------------------------------------------------------------ storage cleanup

    /** videos / songs you haven't opened for [days] days (never opened → counted from when it was saved) */
    fun stale(days: Int): List<LibFile> {
        val limit = System.currentTimeMillis() - days * 86_400_000L
        return Library.files.value
            .filter { (it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO) && it.size > 5L * 1024 * 1024 }
            .filter { maxOf(PlayerHub.lastPlayed(it.path), it.modified) < limit }
            .sortedByDescending { it.size }
    }

    // ------------------------------------------------------------ backup

    private fun text(c: Context, f: File) = if (f.exists()) f.readText().replace(Library.dir.absolutePath, LIB) else null

    /** playlists, categories, folder colours/pins, favorites and resume spots → one zip (the files themselves aren't inside) */
    suspend fun export(c: Context, to: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = c.filesDir
            val sp = c.getSharedPreferences("library", Context.MODE_PRIVATE)
            val pos = c.getSharedPreferences("player_pos", Context.MODE_PRIVATE)
            val prefs = mapOf(
                "fav" to (sp.getStringSet("fav", emptySet()) ?: emptySet()).toList(),
                "pinned" to (sp.getStringSet("pinned", emptySet()) ?: emptySet()).toList(),
                "pos" to pos.all.filter { it.value is Long || it.value is Int }.map { "${it.key}=${it.value}" },
            )
            c.contentResolver.openOutputStream(to)!!.use { out ->
                ZipOutputStream(out).use { z ->
                    fun put(name: String, body: String) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
                    listOf("playlists.json", "categories.json", "category_prefs.json", "folders.json").forEach { n ->
                        text(c, File(dir, n))?.let { put(n, it) }
                    }
                    put("prefs.json", Json.encodeToString(prefs).replace(Library.dir.absolutePath, LIB))
                }
            }
            true
        }.getOrDefault(false)
    }

    /** puts a backup back (replaces playlists / categories / favorites on this phone) */
    suspend fun import(c: Context, from: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val lib = Library.dir.absolutePath
            val parts = HashMap<String, String>()
            c.contentResolver.openInputStream(from)!!.use { ins ->
                ZipInputStream(ins).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        parts[e.name] = z.readBytes().toString(Charsets.UTF_8).replace(LIB, lib)
                    }
                }
            }
            require(parts.isNotEmpty())
            parts.forEach { (n, body) -> if (n != "prefs.json") File(c.filesDir, n).writeText(body) }
            parts["prefs.json"]?.let { body ->
                val m = Json.decodeFromString<Map<String, List<String>>>(body)
                c.getSharedPreferences("library", Context.MODE_PRIVATE).edit()
                    .putStringSet("fav", m["fav"].orEmpty().toSet()).putStringSet("pinned", m["pinned"].orEmpty().toSet()).apply()
                val ed = c.getSharedPreferences("player_pos", Context.MODE_PRIVATE).edit()
                m["pos"].orEmpty().forEach { kv ->
                    val k = kv.substringBeforeLast('=')
                    kv.substringAfterLast('=').toLongOrNull()?.let { v -> ed.putLong(k, v) }
                }
                ed.apply()
            }
            true
        }.getOrDefault(false).also { ok -> if (ok) withContext(Dispatchers.Main) { Library.init(c); PlayerHub.loadRecent() } }
    }
}
