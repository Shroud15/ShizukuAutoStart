package com.helper.shizukuautostart

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/**
 * Applies the saved light/dark choice and, on Android 12+, the system's
 * dynamic (Material You) colour palette before any activity is created.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("helper", Context.MODE_PRIVATE)
        val systemDark = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val followsSystem = !prefs.contains("dark")
        val dark = prefs.getBoolean("dark", systemDark)

        AppCompatDelegate.setDefaultNightMode(
            when {
                followsSystem -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                dark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_NO
            }
        )

        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
