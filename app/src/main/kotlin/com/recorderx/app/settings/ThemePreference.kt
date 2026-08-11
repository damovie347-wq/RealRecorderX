package com.recorderx.app.settings

import android.content.Context

enum class ThemeMode(val label: String) {
    LIGHT("LIGHT"),
    DARK("DARK"),
    AMOLED("AMOLED")
}

/** Deliberately its own tiny SharedPreferences file rather than folded into
 * [SettingsRepository] / [RecordingSettings] -- this is a UI presentation
 * choice, not part of what configures a recording session, and RecordingService
 * never needs to read it. */
class ThemePreference(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ThemeMode {
        val raw = prefs.getString(KEY_MODE, null) ?: return ThemeMode.LIGHT
        return try {
            ThemeMode.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            ThemeMode.LIGHT
        }
    }

    fun save(mode: ThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "recorderx_theme"
        private const val KEY_MODE = "theme_mode"
    }
}
