package com.ali.files.vault

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class VaultEntry(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val addedAt: Long,
    val hasThumb: Boolean = false,
)

/**
 * The vault was removed from the app. This is only what's needed to move the files that were in it
 * (encrypted) back into the library, once, after the phone's lock confirms it's you.
 */
object Vault {
    private lateinit var app: Context
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir get() = File(app.filesDir, "vault")
    private val indexFile get() = File(dir, "index.enc")

    /** files still waiting to be moved (0 = nothing left, the vault is gone) */
    val waiting = mutableStateOf(0)

    fun init(c: Context) {
        app = c.applicationContext
        File(app.cacheDir, "vault_open").deleteRecursively()
        waiting.value = runCatching { entries().size }.getOrDefault(0)
        if (waiting.value == 0 && dir.exists()) dir.deleteRecursively()
    }

    private fun entries(): List<VaultEntry> =
        if (!indexFile.exists()) emptyList()
        else json.decodeFromString(String(VaultCrypto.decryptBytes(indexFile.readBytes())))

    /** Decrypts every file into the library folder «من الخزنة», then removes the vault. Returns how many moved. */
    suspend fun moveAllToLibrary(): Int = withContext(Dispatchers.IO) {
        val list = entries()
        var moved = 0
        for (e in list) {
            runCatching {
                val tmp = File(app.cacheDir, "vault_move_${e.id}")
                tmp.outputStream().use { o -> File(dir, "${e.id}.bin").inputStream().use { VaultCrypto.decrypt(it, o) } }
                com.ali.files.library.Library.add(tmp, e.name, folder = "من الخزنة")
                moved++
            }
        }
        if (moved == list.size) dir.deleteRecursively()
        waiting.value = list.size - moved
        moved
    }
}
