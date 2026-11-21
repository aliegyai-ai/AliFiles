package com.ali.files.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import com.ali.files.util.FileKind
import com.ali.files.util.FileStore
import com.ali.files.util.kindOf
import com.ali.files.util.mimeOf
import com.ali.files.util.sanitizeFileName
import com.ali.files.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

data class LibFile(val file: File, val kind: FileKind, val size: Long, val modified: Long) {
    val name: String get() = file.name
    val title: String get() = file.nameWithoutExtension
    val path: String get() = file.absolutePath
    val mime: String get() = mimeOf(file.name)
}

@Serializable
data class Playlist(val name: String, val paths: List<String> = emptyList())

/**
 * Everything the user downloads lives here, inside the app (not in the gallery or the phone's file
 * manager). The user decides what gets saved to the phone, and where.
 */
object Library {
    private lateinit var app: Context
    private val json = Json { ignoreUnknownKeys = true }

    val dir: File get() = File(app.filesDir, "library").apply { mkdirs() }
    private val thumbDir: File get() = File(app.cacheDir, "libthumbs").apply { mkdirs() }
    private val playlistFile: File get() = File(app.filesDir, "playlists.json")

    private val _files = MutableStateFlow<List<LibFile>>(emptyList())
    val files: StateFlow<List<LibFile>> = _files

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    fun init(c: Context) {
        app = c.applicationContext
        _favorites.value = app.getSharedPreferences("library", Context.MODE_PRIVATE).getStringSet("fav", emptySet())?.toSet() ?: emptySet()
        _playlists.value = runCatching { json.decodeFromString<List<Playlist>>(playlistFile.readText()) }.getOrDefault(emptyList())
        refresh()
    }

    fun refresh() {
        _files.value = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { LibFile(it, kindOf(null, it.name), it.length(), it.lastModified()) }
            .sortedByDescending { it.modified }
    }

    /** A free name in the library ("song.mp3" → "song (2).mp3" if taken). */
    fun uniqueFile(name: String): File {
        val clean = sanitizeFileName(name).ifBlank { "file" }
        var f = File(dir, clean)
        var i = 2
        val base = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "").let { if (it.isNotEmpty() && it != clean) ".$it" else "" }
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }

    /** Moves a finished download into the library. */
    fun add(src: File, name: String): File {
        val dest = uniqueFile(name)
        if (!src.renameTo(dest)) {
            src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            src.delete()
        }
        refresh()
        return dest
    }

    fun uriFor(f: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)

    /** «Save to phone» → Download/AliFiles. */
    suspend fun saveToDownloads(f: LibFile): Uri = withContext(Dispatchers.IO) {
        FileStore.saveToDownloads(app, f.file, f.name, f.mime)
    }

    /** «Save to…» → a place the user picked (system «save as» screen). */
    suspend fun saveTo(f: LibFile, target: Uri) = withContext(Dispatchers.IO) {
        val out = app.contentResolver.openOutputStream(target) ?: throw IOException("تعذر الكتابة في المكان ده")
        out.use { o -> f.file.inputStream().use { it.copyTo(o, 256 * 1024) } }
    }

    suspend fun moveToVault(f: LibFile) = withContext(Dispatchers.IO) {
        Vault.importFile(f.file, f.name, f.mime)
        f.file.delete()
        forget(f.path)
        refresh()
    }

    fun delete(f: LibFile) {
        f.file.delete()
        forget(f.path)
        refresh()
    }

    fun rename(f: LibFile, newName: String): File? {
        val ext = f.file.extension
        val wanted = if (ext.isNotEmpty() && !newName.endsWith(".$ext")) "$newName.$ext" else newName
        val dest = uniqueFile(wanted)
        if (!f.file.renameTo(dest)) return null
        // keep favorites / playlists pointing at the file
        if (f.path in _favorites.value) setFavorites(_favorites.value - f.path + dest.absolutePath)
        savePlaylists(_playlists.value.map { p -> p.copy(paths = p.paths.map { if (it == f.path) dest.absolutePath else it }) })
        refresh()
        return dest
    }

    private fun forget(path: String) {
        if (path in _favorites.value) setFavorites(_favorites.value - path)
        if (_playlists.value.any { path in it.paths }) savePlaylists(_playlists.value.map { p -> p.copy(paths = p.paths - path) })
    }

    // ------------------------------------------------------------ favorites & playlists

    fun isFavorite(path: String) = path in _favorites.value
    fun toggleFavorite(path: String) = setFavorites(if (isFavorite(path)) _favorites.value - path else _favorites.value + path)

    private fun setFavorites(s: Set<String>) {
        _favorites.value = s
        app.getSharedPreferences("library", Context.MODE_PRIVATE).edit().putStringSet("fav", s).apply()
    }

    fun createPlaylist(name: String, first: String? = null) {
        if (name.isBlank() || _playlists.value.any { it.name == name }) return
        savePlaylists(_playlists.value + Playlist(name.trim(), listOfNotNull(first)))
    }

    fun addToPlaylist(name: String, path: String) =
        savePlaylists(_playlists.value.map { if (it.name == name && path !in it.paths) it.copy(paths = it.paths + path) else it })

    fun removeFromPlaylist(name: String, path: String) =
        savePlaylists(_playlists.value.map { if (it.name == name) it.copy(paths = it.paths - path) else it })

    fun deletePlaylist(name: String) = savePlaylists(_playlists.value.filterNot { it.name == name })

    private fun savePlaylists(l: List<Playlist>) {
        _playlists.value = l
        runCatching { playlistFile.writeText(json.encodeToString(l)) }
    }

    fun filesOf(p: Playlist): List<LibFile> {
        val byPath = _files.value.associateBy { it.path }
        return p.paths.mapNotNull { byPath[it] }
    }

    // ------------------------------------------------------------ thumbnails & duration

    private val durations = HashMap<String, Long>()

    /** Cover art of a song, or a frame from a video. Cached on disk. */
    suspend fun thumbnail(f: LibFile): Bitmap? = withContext(Dispatchers.IO) {
        if (f.kind != FileKind.VIDEO && f.kind != FileKind.AUDIO && f.kind != FileKind.IMAGE) return@withContext null
        val cache = File(thumbDir, "${f.path.hashCode()}_${f.modified}.jpg")
        if (cache.exists()) return@withContext BitmapFactory.decodeFile(cache.path)
        val bmp = runCatching {
            if (f.kind == FileKind.IMAGE) {
                val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(f.path, o)
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(f.path)
                    r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        ?: if (f.kind == FileKind.VIDEO) r.getFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) else null
                } finally { r.release() }
            }
        }.getOrNull() ?: return@withContext null
        val small = scaleDown(bmp, 480)
        runCatching { cache.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
        small
    }

    /** Length in milliseconds (0 if unknown). */
    suspend fun duration(f: LibFile): Long = withContext(Dispatchers.IO) {
        durations[f.path]?.let { return@withContext it }
        val d = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.path)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { r.release() }
        }.getOrDefault(0L)
        durations[f.path] = d
        d
    }

    fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= max) return b
        val s = max.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    fun totalSize(): Long = _files.value.sumOf { it.size }
}
