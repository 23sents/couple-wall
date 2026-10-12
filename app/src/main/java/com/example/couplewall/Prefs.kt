package com.example.couplewall

import android.content.Context
import java.security.SecureRandom
import kotlin.math.abs

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("couplewall", Context.MODE_PRIVATE)

    var pairCode: String?
        get() = sp.getString("code", null)
        set(v) = putString("code", v)

    var lastMessageId: String?
        get() = sp.getString("last", null)
        set(v) = putString("last", v)

    /** Random id of this phone; part of every stroke id. */
    val deviceId: Int
        get() {
            var id = sp.getInt("device", 0)
            if (id == 0) {
                id = SecureRandom().nextInt()
                if (id == 0) id = 1
                sp.edit().putInt("device", id).apply()
            }
            return id
        }

    /** Wallpaper id the drawing is composed on for the lock screen ("custom" = own photo). */
    var lockBackground: String
        get() = sp.getString("lockbg", "rose") ?: "rose"
        set(v) = putString("lockbg", v)

    /** Keep the lock screen updated with the shared drawing (off until she/he turns it on). */
    var autoLock: Boolean
        get() = sp.getBoolean("autolock", false)
        set(v) { sp.edit().putBoolean("autolock", v).apply() }

    /** Background sync service while the app is closed. */
    var liveService: Boolean
        get() = sp.getBoolean("live", false)
        set(v) { sp.edit().putBoolean("live", v).apply() }

    var colorIndex: Int
        get() = sp.getInt("color", abs(deviceId) % 8)
        set(v) { sp.edit().putInt("color", v).apply() }

    var brushIndex: Int
        get() = sp.getInt("brush", 1)
        set(v) { sp.edit().putInt("brush", v).apply() }

    fun nextSeq(): Int {
        val n = sp.getInt("seq", 0) + 1
        sp.edit().putInt("seq", n).apply()
        return n
    }

    private fun putString(key: String, v: String?) {
        val e = sp.edit()
        if (v == null) e.remove(key) else e.putString(key, v)
        e.apply()
    }
}
