package com.ali.files.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.ali.files.util.FileKind
import com.ali.files.util.FileStore
import com.ali.files.util.kindOf
import com.ali.files.util.mimeOf
import com.ali.files.util.sanitizeFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID

@Serializable
data class VaultEntry(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val addedAt: Long,
    val hasThumb: Boolean = false,
)

private class CountingInputStream(inner: InputStream) : FilterInputStream(inner) {
    var count = 0L
        private set
    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
}

/**
 * Encrypted vault. Files, thumbnails and even the list of file names are stored encrypted
 * in the app's private storage. Importing works while the vault is locked (downloads can
 * land in it in the background); viewing needs the PIN / fingerprint.
 */
object Vault {
    private lateinit var app: Context
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = _entries

    val unlocked = mutableStateOf(false)
    val busy = mutableIntStateOf(0)

    /** Set before launching a picker / external viewer so leaving the app doesn't lock instantly. */
    @Volatile var allowLeave = false
    private var stoppedAt = 0L

    private val thumbCache = LruCache<String, Bitmap>(80)

    private val dir get() = File(app.filesDir, "vault").apply { mkdirs() }
    private val thumbs get() = File(dir, "thumbs").apply { mkdirs() }
    private val indexFile get() = File(dir, "index.enc")
    private val openDir get() = File(app.cacheDir, "vault_open")
    private fun blob(id: String) = File(dir, "$id.bin")
    private fun thumbFile(id: String) = File(thumbs, "$id.enc")

    fun init(c: Context) {
        app = c.applicationContext
        VaultLock.init(app)
        openDir.deleteRecursively()
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                runCatching {
                    if (indexFile.exists()) {
                        _entries.value = json.decodeFromString(String(VaultCrypto.decryptBytes(indexFile.readBytes())))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ lock state

    fun unlock() {
        openDir.deleteRecursively()
        unlocked.value = true
    }

    fun lock() {
        unlocked.value = false
        thumbCache.evictAll()
    }

    fun onAppStop() {
        stoppedAt = System.currentTimeMillis()
        if (!allowLeave) lock()
    }

    fun onAppStart() {
        if (allowLeave && System.currentTimeMillis() - stoppedAt > 2 * 60_000) lock()
        allowLeave = false
    }

    // ------------------------------------------------------------ index

    private fun saveIndex() {
        val tmp = File(dir, "index.tmp")
        tmp.writeBytes(VaultCrypto.encryptBytes(json.encodeToString(_entries.value).toByteArray()))
        if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
    }

    // ------------------------------------------------------------ import

    suspend fun importFile(src: File, name: String, mime: String): VaultEntry =
        importInternal(name, mime, { src.inputStream() }) { thumbFromFile(src, mime, name) }

    suspend fun importUri(uri: Uri, nameHint: String? = null): VaultEntry {
        val resolver = app.contentResolver
        val name = nameHint?.takeIf { it.isNotBlank() } ?: FileStore.displayName(app, uri)
        val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" } ?: mimeOf(name)
        return importInternal(name, mime, { resolver.openInputStream(uri) ?: throw IOException("تعذر فتح الملف") }) {
            thumbFromUri(uri, mime, name)
        }
    }

    private suspend fun importInternal(
        name: String,
        mime: String,
        open: () -> InputStream,
        thumb: () -> Bitmap?,
    ): VaultEntry = withContext(Dispatchers.IO) {
        busy.intValue++
        try {
            val id = UUID.randomUUID().toString()
            val f = blob(id)
            var size = 0L
            try {
                FileOutputStream(f).use { out ->
                    CountingInputStream(open()).use { inp ->
                        VaultCrypto.encrypt(inp, out)
                        size = inp.count
                    }
                }
            } catch (e: Exception) {
                f.delete()
                throw e
            }
            val bmp = runCatching { thumb() }.getOrNull()
            var hasThumb = false
            if (bmp != null) {
                runCatching {
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                    thumbFile(id).writeBytes(VaultCrypto.encryptBytes(bos.toByteArray()))
                    hasThumb = true
                }
            }
            val entry = VaultEntry(id, sanitizeFileName(name), mime, size, System.currentTimeMillis(), hasThumb)
            mutex.withLock {
                _entries.update { listOf(entry) + it }
                saveIndex()
            }
            entry
        } finally {
            busy.intValue--
        }
    }

    /** Tries to delete the original after import. Returns false if the provider doesn't allow it. */
    fun deleteOriginal(uri: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(app.contentResolver, uri)
    }.getOrElse { runCatching { app.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false) }

    // ------------------------------------------------------------ thumbnails

    private fun scaled(w: Int, h: Int, max: Int): Pair<Int, Int> {
        val s = max.toFloat() / maxOf(w, h)
        return if (s >= 1f) w to h else (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1)
    }

    private fun decodeSource(src: ImageDecoder.Source, max: Int): Bitmap =
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val (w, h) = scaled(info.size.width, info.size.height, max)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun thumbFromFile(f: File, mime: String, name: String): Bitmap? = when (kindOf(mime, name)) {
        FileKind.IMAGE -> decodeSource(ImageDecoder.createSource(f), 320)
        FileKind.VIDEO -> MediaMetadataRetriever().run {
            try { setDataSource(f.absolutePath); getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320) }
            finally { release() }
        }
        else -> null
    }

    private fun thumbFromUri(uri: Uri, mime: String, name: String): Bitmap? = when (kindOf(mime, name)) {
        FileKind.IMAGE -> decodeSource(ImageDecoder.createSource(app.contentResolver, uri), 320)
        FileKind.VIDEO -> MediaMetadataRetriever().run {
            try { setDataSource(app, uri); getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320) }
            finally { release() }
        }
        else -> null
    }

    suspend fun thumbnail(e: VaultEntry): Bitmap? {
        if (!e.hasThumb || !unlocked.value) return null
        thumbCache.get(e.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = VaultCrypto.decryptBytes(thumbFile(e.id).readBytes())
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()?.also { thumbCache.put(e.id, it) }
        }
    }

    // ------------------------------------------------------------ reading

    suspend fun decodeImage(e: VaultEntry, max: Int = 2560): Bitmap = withContext(Dispatchers.IO) {
        check(unlocked.value) { "الخزنة مقفولة" }
        val bos = ByteArrayOutputStream(e.size.coerceIn(1024L, 64L shl 20).toInt())
        blob(e.id).inputStream().use { VaultCrypto.decrypt(it, bos) }
        decodeSource(ImageDecoder.createSource(ByteBuffer.wrap(bos.toByteArray())), max)
    }

    /** Decrypts to a private cache file (wiped on next unlock / app start) for opening in another app. */
    suspend fun decryptToCache(e: VaultEntry): File = withContext(Dispatchers.IO) {
        check(unlocked.value) { "الخزنة مقفولة" }
        busy.intValue++
        try {
            val folder = File(openDir, e.id).apply { mkdirs() }
            val out = File(folder, e.name)
            if (!out.exists() || out.length() != e.size) {
                FileOutputStream(out).use { o -> blob(e.id).inputStream().use { VaultCrypto.decrypt(it, o) } }
            }
            out
        } finally {
            busy.intValue--
        }
    }

    /** Decrypts back to Download/AliFiles. */
    suspend fun export(e: VaultEntry): Uri = withContext(Dispatchers.IO) {
        check(unlocked.value) { "الخزنة مقفولة" }
        busy.intValue++
        try {
            FileStore.createInDownloads(app, e.name, e.mime) { out ->
                blob(e.id).inputStream().use { VaultCrypto.decrypt(it, out) }
            }
        } finally {
            busy.intValue--
        }
    }

    suspend fun delete(ids: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ids.forEach { blob(it).delete(); thumbFile(it).delete(); thumbCache.remove(it) }
            _entries.update { l -> l.filterNot { it.id in ids } }
            saveIndex()
        }
    }

    suspend fun rename(id: String, newName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _entries.update { l -> l.map { if (it.id == id) it.copy(name = sanitizeFileName(newName)) else it } }
            saveIndex()
        }
    }

    fun totalSize(): Long = _entries.value.sumOf { it.size }
}
