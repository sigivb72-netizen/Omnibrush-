package dev.omnimirror.sender

import android.content.Context
import android.os.Build
import android.provider.Settings as SystemSettings
import dev.omnimirror.core.Auth

/** User preferences, persisted across launches. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("omnimirror", Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver

    /** Length of the shorter screen edge in the stream, in pixels. */
    var shortSide: Int
        get() = prefs.getInt("shortSide", 1080)
        set(v) = prefs.edit().putInt("shortSide", v).apply()

    var fps: Int
        get() = prefs.getInt("fps", 60)
        set(v) = prefs.edit().putInt("fps", v).apply()

    var maxBitrateKbps: Int
        get() = prefs.getInt("maxBitrateKbps", 8000)
        set(v) = prefs.edit().putInt("maxBitrateKbps", v).apply()

    var adaptive: Boolean
        get() = prefs.getBoolean("adaptive", true)
        set(v) = prefs.edit().putBoolean("adaptive", v).apply()

    /** The code a receiver must know to connect to this phone. */
    var pin: String
        get() = prefs.getString("pin", null) ?: Auth.newPin().also { pin = it }
        set(v) = prefs.edit().putString("pin", v).apply()

    val deviceName: String
        get() = runCatching { SystemSettings.Global.getString(resolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"

    companion object {
        val RESOLUTIONS = intArrayOf(1080, 720, 540)
        val FRAME_RATES = intArrayOf(60, 30)
        val BITRATES_KBPS = intArrayOf(4000, 8000, 12000, 20000)
        const val MIN_BITRATE = 500_000
    }
}
