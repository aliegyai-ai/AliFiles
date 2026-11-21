package com.ali.files.vault

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN handling: salted PBKDF2 hash, increasing lockout after wrong attempts. */
object VaultLock {
    private lateinit var sp: SharedPreferences
    private const val ITER = 60_000

    fun init(c: Context) {
        sp = c.getSharedPreferences("vault_lock", Context.MODE_PRIVATE)
    }

    fun hasPin(): Boolean = sp.contains("hash")

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded

    /** Slow (PBKDF2) — call from a background dispatcher. */
    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt("fails", 0)
            .putLong("lockUntil", 0)
            .apply()
    }

    /** Milliseconds the user still has to wait before trying again (0 = can try). */
    fun lockedFor(): Long = (sp.getLong("lockUntil", 0) - System.currentTimeMillis()).coerceAtLeast(0)

    /** Slow (PBKDF2) — call from a background dispatcher. */
    fun check(pin: String): Boolean {
        if (lockedFor() > 0) return false
        val salt = Base64.decode(sp.getString("salt", "") ?: "", Base64.NO_WRAP)
        val expected = Base64.decode(sp.getString("hash", "") ?: "", Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(hash(pin, salt), expected)
        if (ok) {
            sp.edit().putInt("fails", 0).putLong("lockUntil", 0).apply()
        } else {
            val fails = sp.getInt("fails", 0) + 1
            val wait = when {
                fails >= 10 -> 10 * 60_000L
                fails >= 5 -> 30_000L * (fails - 4)
                else -> 0L
            }
            sp.edit().putInt("fails", fails).putLong("lockUntil", System.currentTimeMillis() + wait).apply()
        }
        return ok
    }

    fun fails(): Int = sp.getInt("fails", 0)
}
