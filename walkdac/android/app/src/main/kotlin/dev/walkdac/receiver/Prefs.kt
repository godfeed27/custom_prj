package dev.walkdac.receiver

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("walkdac", Context.MODE_PRIVATE)

    /** Manually entered sender host, or empty to use discovery. */
    var host: String
        get() = sp.getString("host", "") ?: ""
        set(value) = sp.edit().putString("host", value).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean("auto", true)
        set(value) = sp.edit().putBoolean("auto", value).apply()

    var targetMs: Int
        get() = sp.getInt("target_ms", 450)
        set(value) = sp.edit().putInt("target_ms", value).apply()

    var audioPort: Int
        get() = sp.getInt("audio_port", dev.walkdac.core.Protocol.DEFAULT_AUDIO_PORT)
        set(value) = sp.edit().putInt("audio_port", value).apply()

    var controlPort: Int
        get() = sp.getInt("control_port", dev.walkdac.core.Protocol.DEFAULT_CONTROL_PORT)
        set(value) = sp.edit().putInt("control_port", value).apply()
}
