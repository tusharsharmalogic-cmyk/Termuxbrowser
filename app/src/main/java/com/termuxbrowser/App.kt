package com.termuxbrowser

import android.app.Application
import android.content.Context
import java.security.SecureRandom

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        BrowserHolder.init(this)
    }
}

object Cfg {
    /** Random token jo Termux se aane wali har request me chahiye (X-Token header). */
    fun token(ctx: Context): String {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        var t = p.getString("token", null)
        if (t == null) {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            t = b.joinToString("") { "%02x".format(it) }
            p.edit().putString("token", t).apply()
        }
        return t
    }
}
