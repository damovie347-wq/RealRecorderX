package com.recorderx.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.recorderx.app.settings.ThemeMode
import com.recorderx.app.settings.ThemePreference

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Applied here, not in MainActivity#onCreate, so it's already in
        // effect before the first Activity's window is even created --
        // otherwise the light theme would flash briefly before switching.
        val mode = ThemePreference(this).load()
        AppCompatDelegate.setDefaultNightMode(
            if (mode == ThemeMode.LIGHT) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
    }
}
