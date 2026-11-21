package com.ali.files.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ali.files.util.readFully
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * File format "AVF1":
 *   magic(4) | ivLen(1) | wrapIv | keyLen(2) | wrappedKey | { chunkLen(4) | iv(12) | AES-GCM(chunk) }* | 0(4)
 *
 * Every file gets its own random 256-bit key. That key is wrapped with a master key that lives
 * in the phone's hardware keystore and can never be exported. Chunks are 1 MB and the chunk
 * index is bound as AAD, so chunks can't be reordered; the 0 end-marker detects truncation.
 */
object VaultCrypto {
    private const val ALIAS = "ali_vault_master_v1"
    private const val CHUNK = 1 shl 20
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    private val rnd = SecureRandom()

    @Volatile private var cached: SecretKey? = null

    private fun masterKey(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = ks.getKey(ALIAS, null) as? SecretKey
            val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
            cached = key
            return key
        }
    }

    private fun aad(index: Long): ByteArray = ByteBuffer.allocate(8).putLong(index).array()

    fun encrypt(input: InputStream, output: OutputStream) {
        val dek = ByteArray(32).also { rnd.nextBytes(it) }
        try {
            val wrap = Cipher.getInstance("AES/GCM/NoPadding")
            wrap.init(Cipher.ENCRYPT_MODE, masterKey())
            val wrapped = wrap.doFinal(dek)
            val wrapIv = wrap.iv
            val out = DataOutputStream(BufferedOutputStream(output, 1 shl 16))
            out.write(MAGIC)
            out.writeByte(wrapIv.size)
            out.write(wrapIv)
            out.writeShort(wrapped.size)
            out.write(wrapped)
            val key = SecretKeySpec(dek, "AES")
            val buf = ByteArray(CHUNK)
            var index = 0L
            while (true) {
                val n = input.readFully(buf)
                if (n <= 0) break
                val iv = ByteArray(12).also { rnd.nextBytes(it) }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                c.updateAAD(aad(index))
                val ct = c.doFinal(buf, 0, n)
                out.writeInt(ct.size)
                out.write(iv)
                out.write(ct)
                index++
                if (n < CHUNK) break
            }
            out.writeInt(0)
            out.flush()
        } finally {
            dek.fill(0)
        }
    }

    fun decrypt(input: InputStream, output: OutputStream) {
        val din = DataInputStream(BufferedInputStream(input, 1 shl 16))
        val m = ByteArray(4)
        din.readFully(m)
        if (!m.contentEquals(MAGIC)) throw IOException("ملف الخزنة تالف")
        val wrapIv = ByteArray(din.readUnsignedByte()).also { din.readFully(it) }
        val wrapped = ByteArray(din.readUnsignedShort()).also { din.readFully(it) }
        val unwrap = Cipher.getInstance("AES/GCM/NoPadding")
        unwrap.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, wrapIv))
        val dek = unwrap.doFinal(wrapped)
        try {
            val key = SecretKeySpec(dek, "AES")
            var index = 0L
            while (true) {
                val len = din.readInt()
                if (len == 0) break
                if (len < 0 || len > CHUNK + 64) throw IOException("ملف الخزنة تالف")
                val iv = ByteArray(12).also { din.readFully(it) }
                val ct = ByteArray(len).also { din.readFully(it) }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                c.updateAAD(aad(index))
                output.write(c.doFinal(ct))
                index++
            }
            output.flush()
        } finally {
            dek.fill(0)
        }
    }

    fun encryptBytes(b: ByteArray): ByteArray =
        ByteArrayOutputStream(b.size + 128).also { encrypt(b.inputStream(), it) }.toByteArray()

    fun decryptBytes(b: ByteArray): ByteArray =
        ByteArrayOutputStream(b.size).also { decrypt(b.inputStream(), it) }.toByteArray()
}
