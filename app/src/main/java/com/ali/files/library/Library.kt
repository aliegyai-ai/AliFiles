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

data class LibFile(val file: File, val kind: FileKind, val size: Long, val modified: Long, val folder: String = "") {
    val name: String get() = file.name
    val title: String get() = file.nameWithoutExtension
    val path: String get() = file.absolutePath
    val mime: String get() = mimeOf(file.name)
}

/** A folder in the library ([rel] = path inside the library, e.g. "أفلام/أكشن"). */
data class LibFolder(val rel: String, val count: Int, val size: Long, val modified: Long) {
    val name: String get() = rel.substringAfterLast('/')
    val parent: String get() = if ('/' in rel) rel.substringBeforeLast('/') else ""
}

@Serializable
data class FolderMeta(val color: Int = 0, val locked: Boolean = false)

/** Something in the trash (kept 30 days). */
@Serializable
data class TrashEntry(val id: String, val name: String, val from: String, val deletedAt: Long, val size: Long, val isDir: Boolean = false)

/** A category the user made (e.g. «أناشيد»). */
@Serializable
data class CustomCat(val id: String, val name: String, val color: Int, val icon: Int = 0)

enum class LibSort(val label: String) { DATE("الأحدث"), NAME("الاسم"), SIZE("الحجم"), TYPE("النوع") }

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
        _meta.value = runCatching { json.decodeFromString<Map<String, FolderMeta>>(metaFile.readText()) }.getOrDefault(emptyMap())
        _pinned.value = prefs.getStringSet("pinned", emptySet())?.toSet() ?: emptySet()
        runCatching { json.decodeFromString<CatStore>(catsFile.readText()) }.getOrNull()?.let { _cats.value = it.assigned; _custom.value = it.custom }
        sort.value = runCatching { LibSort.valueOf(prefs.getString("sort", "DATE")!!) }.getOrDefault(LibSort.DATE)
        // the trash keeps things 30 days
        val all = runCatching { json.decodeFromString<List<TrashEntry>>(trashIndex.readText()) }.getOrDefault(emptyList())
        val old = all.filter { System.currentTimeMillis() - it.deletedAt > 30L * 86_400_000 }
        old.forEach { File(trashDir, it.id).deleteRecursively() }
        _trash.value = all - old.toSet()
        if (old.isNotEmpty()) saveTrash(_trash.value)
        _favorites.value = app.getSharedPreferences("library", Context.MODE_PRIVATE).getStringSet("fav", emptySet())?.toSet() ?: emptySet()
        _playlists.value = runCatching { json.decodeFromString<List<Playlist>>(playlistFile.readText()) }.getOrDefault(emptyList())
        refresh()
    }

    private val _folders = MutableStateFlow<List<LibFolder>>(emptyList())
    val folders: StateFlow<List<LibFolder>> = _folders
    private val _meta = MutableStateFlow<Map<String, FolderMeta>>(emptyMap())
    val meta: StateFlow<Map<String, FolderMeta>> = _meta
    /** pinned files and folders (shown first) */
    private val _pinned = MutableStateFlow<Set<String>>(emptySet())
    val pinned: StateFlow<Set<String>> = _pinned
    private val _trash = MutableStateFlow<List<TrashEntry>>(emptyList())
    val trash: StateFlow<List<TrashEntry>> = _trash
    val sort = androidx.compose.runtime.mutableStateOf(LibSort.DATE)

    /** categories you chose by hand: file path → "VIDEO" / "AUDIO" / "NOMUSIC" / "LONG" / "IMAGE" / "OTHER" / "c:<id>" */
    private val _cats = MutableStateFlow<Map<String, String>>(emptyMap())
    val cats: StateFlow<Map<String, String>> = _cats
    private val _custom = MutableStateFlow<List<CustomCat>>(emptyList())
    val custom: StateFlow<List<CustomCat>> = _custom
    private val catsFile get() = File(app.filesDir, "categories.json")

    @Serializable
    private data class CatStore(val assigned: Map<String, String> = emptyMap(), val custom: List<CustomCat> = emptyList())

    private fun saveCats() = runCatching { catsFile.writeText(json.encodeToString(CatStore(_cats.value, _custom.value))) }

    fun setCategory(files: List<LibFile>, cat: String?) {
        _cats.value = _cats.value.toMutableMap().apply { files.forEach { if (cat == null) remove(it.path) else put(it.path, cat) } }
        saveCats()
    }

    fun addCustom(name: String, color: Int, icon: Int): CustomCat {
        val c = CustomCat(System.currentTimeMillis().toString(36), name.trim(), color, icon)
        _custom.value = _custom.value + c; saveCats(); return c
    }

    fun editCustom(c: CustomCat) { _custom.value = _custom.value.map { if (it.id == c.id) c else it }; saveCats() }

    fun deleteCustom(id: String) {
        _custom.value = _custom.value.filterNot { it.id == id }
        _cats.value = _cats.value.filterValues { it != "c:$id" }
        saveCats()
    }

    private val metaFile get() = File(app.filesDir, "folders.json")
    private val trashDir get() = File(app.filesDir, "trash").apply { mkdirs() }
    private val trashIndex get() = File(trashDir, "index.json")
    private val prefs get() = app.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun rel(f: File): String = f.absolutePath.removePrefix(dir.absolutePath).trim('/')
    fun folderDir(rel: String): File = if (rel.isEmpty()) dir else File(dir, rel)

    fun refresh() {
        val root = dir
        val all = ArrayList<LibFile>()
        val dirs = ArrayList<LibFolder>()
        root.walkTopDown().onEnter { !it.name.startsWith(".") || it == root }.forEach { f ->
            if (f == root || f.name.startsWith(".")) return@forEach
            if (f.isFile) {
                val parent = f.parentFile?.let { rel(it) } ?: ""
                all += LibFile(f, kindOf(null, f.name), f.length(), f.lastModified(), parent)
            }
        }
        root.walkTopDown().onEnter { !it.name.startsWith(".") || it == root }.filter { it.isDirectory && it != root && !it.name.startsWith(".") }.forEach { d ->
            val inside = d.walkTopDown().filter { it.isFile }.toList()
            dirs += LibFolder(rel(d), inside.size, inside.sumOf { it.length() }, d.lastModified())
        }
        _files.value = all.sortedByDescending { it.modified }
        _folders.value = dirs
    }

    fun sorted(l: List<LibFile>): List<LibFile> {
        val base = when (sort.value) {
            LibSort.DATE -> l.sortedByDescending { it.modified }
            LibSort.NAME -> l.sortedBy { it.name.lowercase() }
            LibSort.SIZE -> l.sortedByDescending { it.size }
            LibSort.TYPE -> l.sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))
        }
        val pins = _pinned.value
        return base.sortedByDescending { it.path in pins }
    }

    fun sortedFolders(l: List<LibFolder>): List<LibFolder> {
        val base = when (sort.value) {
            LibSort.SIZE -> l.sortedByDescending { it.size }
            LibSort.DATE -> l.sortedByDescending { it.modified }
            else -> l.sortedBy { it.name.lowercase() }
        }
        val pins = _pinned.value
        return base.sortedByDescending { "dir:" + it.rel in pins }
    }

    fun setSort(s: LibSort) { sort.value = s; prefs.edit().putString("sort", s.name).apply() }

    // ------------------------------------------------------------ folders

    fun createFolder(parent: String, name: String): Boolean {
        val clean = sanitizeFileName(name.trim()).ifBlank { return false }
        val d = File(folderDir(parent), clean)
        if (d.exists()) return false
        val ok = d.mkdirs()
        refresh()
        return ok
    }

    fun renameFolder(f: LibFolder, name: String): Boolean {
        val clean = sanitizeFileName(name.trim()).ifBlank { return false }
        val src = folderDir(f.rel)
        val dest = File(src.parentFile, clean)
        if (dest.exists() || !src.renameTo(dest)) return false
        val newRel = rel(dest)
        remapPaths(src.absolutePath, dest.absolutePath)
        _meta.value[f.rel]?.let { m -> saveMeta(_meta.value - f.rel + (newRel to m)) }
        if ("dir:" + f.rel in _pinned.value) savePinned(_pinned.value - ("dir:" + f.rel) + ("dir:$newRel"))
        refresh()
        return true
    }

    fun setColor(rel: String, color: Int) = saveMeta(_meta.value + (rel to (_meta.value[rel] ?: FolderMeta()).copy(color = color)))
    fun setLocked(rel: String, locked: Boolean) = saveMeta(_meta.value + (rel to (_meta.value[rel] ?: FolderMeta()).copy(locked = locked)))

    /** a folder (or one of its parents) is locked */
    fun isLocked(rel: String): Boolean {
        var r = rel
        while (r.isNotEmpty()) {
            if (_meta.value[r]?.locked == true) return true
            r = if ('/' in r) r.substringBeforeLast('/') else ""
        }
        return false
    }

    private fun saveMeta(m: Map<String, FolderMeta>) {
        _meta.value = m
        runCatching { metaFile.writeText(json.encodeToString(m)) }
    }

    fun togglePin(key: String) = savePinned(if (key in _pinned.value) _pinned.value - key else _pinned.value + key)
    private fun savePinned(s: Set<String>) { _pinned.value = s; prefs.edit().putStringSet("pinned", s).apply() }

    /** Moves files into a folder ("" = the library's top). */
    fun move(files: List<LibFile>, target: String): Int {
        val to = folderDir(target).apply { mkdirs() }
        var n = 0
        files.forEach { f ->
            if (f.folder == target) return@forEach
            val dest = uniqueFile(f.name, to)
            if (f.file.renameTo(dest)) { remapPaths(f.path, dest.absolutePath); n++ }
        }
        refresh()
        return n
    }

    fun copy(f: LibFile): File {
        val dest = uniqueFile(f.name, f.file.parentFile ?: dir)
        f.file.copyTo(dest)
        refresh()
        return dest
    }

    /** Puts files into one .zip next to them. */
    suspend fun zip(files: List<LibFile>, name: String, folder: String): File = withContext(Dispatchers.IO) {
        val dest = uniqueFile(if (name.endsWith(".zip")) name else "$name.zip", folderDir(folder))
        java.util.zip.ZipOutputStream(dest.outputStream().buffered()).use { z ->
            val used = HashSet<String>()
            files.forEach { f ->
                var entry = f.name; var i = 2
                while (!used.add(entry)) entry = "${f.title} (${i++}).${f.file.extension}"
                z.putNextEntry(java.util.zip.ZipEntry(entry))
                f.file.inputStream().use { it.copyTo(z, 256 * 1024) }
                z.closeEntry()
            }
        }
        refresh()
        dest
    }

    /** favorites / playlists / pins follow a file (or everything under a folder) that moved */
    private fun remapPaths(from: String, to: String) {
        fun m(p: String) = if (p == from) to else if (p.startsWith("$from/")) to + p.removePrefix(from) else p
        setFavorites(_favorites.value.map(::m).toSet())
        savePlaylists(_playlists.value.map { pl -> pl.copy(paths = pl.paths.map(::m)) })
        savePinned(_pinned.value.map { if (it.startsWith("dir:")) it else m(it) }.toSet())
        if (_cats.value.keys.any { it == from || it.startsWith("$from/") }) { _cats.value = _cats.value.mapKeys { (k, _) -> m(k) }; saveCats() }
    }

    // ------------------------------------------------------------ trash

    private fun saveTrash(l: List<TrashEntry>) {
        _trash.value = l
        runCatching { trashIndex.writeText(json.encodeToString(l)) }
    }

    private fun toTrash(src: File, isDir: Boolean) {
        val id = System.currentTimeMillis().toString(36) + (0..9999).random()
        val d = File(trashDir, id).apply { mkdirs() }
        val dest = File(d, src.name)
        val size = if (isDir) src.walkTopDown().filter { it.isFile }.sumOf { it.length() } else src.length()
        if (!src.renameTo(dest)) { src.copyRecursively(dest, true); src.deleteRecursively() }
        saveTrash(_trash.value + TrashEntry(id, src.name, rel(src.parentFile ?: dir), System.currentTimeMillis(), size, isDir))
    }

    fun deleteFolder(f: LibFolder) {
        toTrash(folderDir(f.rel), true)
        saveMeta(_meta.value.filterKeys { it != f.rel && !it.startsWith(f.rel + "/") })
        savePinned(_pinned.value - ("dir:" + f.rel))
        refresh()
    }

    fun deleteMany(l: List<LibFile>) { l.forEach { toTrash(it.file, false); forget(it.path) }; refresh() }

    fun restore(e: TrashEntry): Boolean {
        val src = File(File(trashDir, e.id), e.name)
        val parent = folderDir(e.from).apply { mkdirs() }
        val dest = if (e.isDir) File(parent, e.name).let { if (it.exists()) File(parent, e.name + " (مسترجع)") else it } else uniqueFile(e.name, parent)
        val ok = src.renameTo(dest)
        if (ok) { File(trashDir, e.id).deleteRecursively(); saveTrash(_trash.value - e) }
        refresh()
        return ok
    }

    fun purge(e: TrashEntry) { File(trashDir, e.id).deleteRecursively(); saveTrash(_trash.value - e) }
    fun emptyTrash() { _trash.value.forEach { File(trashDir, it.id).deleteRecursively() }; saveTrash(emptyList()) }

    // ------------------------------------------------------------ to the phone

    /** Copies into the phone's gallery / music (Movies, Pictures, Music → AliFiles). */
    suspend fun saveToGallery(f: LibFile): Uri = withContext(Dispatchers.IO) {
        val r = app.contentResolver
        val (collection, rel) = when (f.kind) {
            FileKind.VIDEO -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI to android.os.Environment.DIRECTORY_MOVIES
            FileKind.IMAGE -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI to android.os.Environment.DIRECTORY_PICTURES
            FileKind.AUDIO -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to android.os.Environment.DIRECTORY_MUSIC
            else -> return@withContext FileStore.saveToDownloads(app, f.file, f.name, f.mime)
        }
        val v = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, f.mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$rel/AliFiles")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(collection, v) ?: throw IOException("تعذر الحفظ في الاستديو")
        try {
            r.openOutputStream(uri)!!.use { o -> f.file.inputStream().use { it.copyTo(o, 256 * 1024) } }
            v.clear(); v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, v, null, null)
        } catch (e: Exception) { r.delete(uri, null, null); throw e }
        uri
    }

    enum class ToneType(val label: String, val rm: Int) {
        CALL("نغمة الاتصال", android.media.RingtoneManager.TYPE_RINGTONE),
        NOTIFICATION("نغمة الإشعارات", android.media.RingtoneManager.TYPE_NOTIFICATION),
        ALARM("نغمة المنبه", android.media.RingtoneManager.TYPE_ALARM),
    }

    /** Needs «تعديل إعدادات النظام» permission (Settings.System.canWrite). */
    suspend fun setAsTone(f: LibFile, type: ToneType) = withContext(Dispatchers.IO) {
        val r = app.contentResolver
        val dirName = when (type) {
            ToneType.CALL -> android.os.Environment.DIRECTORY_RINGTONES
            ToneType.NOTIFICATION -> android.os.Environment.DIRECTORY_NOTIFICATIONS
            ToneType.ALARM -> android.os.Environment.DIRECTORY_ALARMS
        }
        val v = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, f.mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, dirName)
            put(android.provider.MediaStore.Audio.Media.IS_RINGTONE, type == ToneType.CALL)
            put(android.provider.MediaStore.Audio.Media.IS_NOTIFICATION, type == ToneType.NOTIFICATION)
            put(android.provider.MediaStore.Audio.Media.IS_ALARM, type == ToneType.ALARM)
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: throw IOException("تعذر حفظ النغمة")
        r.openOutputStream(uri)!!.use { o -> f.file.inputStream().use { it.copyTo(o) } }
        v.clear(); v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
        r.update(uri, v, null, null)
        android.media.RingtoneManager.setActualDefaultRingtoneUri(app, type.rm, uri)
    }

    /** A free name in [inDir] ("song.mp3" → "song (2).mp3" if taken). */
    fun uniqueFile(name: String, inDir: File = dir): File {
        val dir = inDir
        val clean = sanitizeFileName(name).ifBlank { "file" }
        var f = File(dir, clean)
        var i = 2
        val base = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "").let { if (it.isNotEmpty() && it != clean) ".$it" else "" }
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }

    /** Moves a finished download into the library ([source] = the site, for «a folder per site»). */
    fun add(src: File, name: String, source: String? = null): File {
        val sub = when (com.ali.files.util.AppSettings.autoFolder.value) {
            "type" -> when (kindOf(null, name)) {
                FileKind.VIDEO -> "فيديوهات"; FileKind.AUDIO -> "أغاني وصوتيات"; FileKind.IMAGE -> "صور"
                FileKind.ARCHIVE -> "ملفات مضغوطة"; else -> "مستندات وملفات"
            }
            "source" -> source?.let { siteName(it) }
            else -> null
        }
        val dest = uniqueFile(name, if (sub != null) File(dir, sub).apply { mkdirs() } else dir)
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

    /** to the trash (can be restored for 30 days) */
    fun delete(f: LibFile) {
        toTrash(f.file, false)
        forget(f.path)
        refresh()
    }

    fun siteName(url: String): String? {
        val h = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        return when {
            "youtube" in h || h == "youtu.be" -> "YouTube"
            "tiktok" in h -> "TikTok"
            "facebook" in h || h == "fb.watch" -> "Facebook"
            "instagram" in h -> "Instagram"
            h == "x.com" || "twitter" in h -> "X"
            "soundcloud" in h -> "SoundCloud"
            else -> h.substringBeforeLast('.').substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { null }
        }
    }

    fun rename(f: LibFile, newName: String): File? {
        val ext = f.file.extension
        val wanted = if (ext.isNotEmpty() && !newName.endsWith(".$ext")) "$newName.$ext" else newName
        val dest = uniqueFile(wanted, f.file.parentFile ?: dir)
        if (!f.file.renameTo(dest)) return null
        // keep favorites / playlists / pins / categories pointing at the file
        remapPaths(f.path, dest.absolutePath)
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
