package com.ali.files.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableLongStateOf

/** How many ads / trackers / pop-ups were blocked since the app was installed. */
object AdStats {
    val total = mutableLongStateOf(0L)
    private lateinit var sp: android.content.SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pending = 0L

    fun init(c: Context) {
        sp = c.getSharedPreferences("adfilter", Context.MODE_PRIVATE)
        total.longValue = sp.getLong("total", 0)
    }

    /** Called from any thread; saved in batches. */
    fun add(n: Int) {
        synchronized(this) { pending += n }
        main.removeCallbacks(flush)
        main.postDelayed(flush, 1500)
    }

    private val flush = Runnable {
        val p = synchronized(this) { val x = pending; pending = 0; x }
        if (p > 0 && ::sp.isInitialized) {
            total.longValue += p
            sp.edit().putLong("total", total.longValue).apply()
        }
    }
}
