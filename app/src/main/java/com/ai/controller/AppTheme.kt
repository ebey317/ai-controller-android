package com.ai.controller

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.ai.controller.R

/**
 * User-selectable app theme. SYSTEM/LIGHT/DARK ride AppCompat's DayNight
 * (values/ + values-night/ color folders); HIGH_CONTRAST swaps to a dedicated
 * black-on-yellow palette so low-vision users get maximum figure/ground
 * separation. This is an accessibility feature, not just eye candy — it is
 * part of why the app declares isAccessibilityTool=true.
 */
enum class AppTheme(val label: String, val nightMode: Int) {
    SYSTEM("System default", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("Light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("Dark", AppCompatDelegate.MODE_NIGHT_YES),
    HIGH_CONTRAST("High contrast", AppCompatDelegate.MODE_NIGHT_YES),
    GREEN_ON_BLACK("Green on black", AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun from(name: String?): AppTheme =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * Persists the selected theme, same MODE_PRIVATE SharedPreferences pattern as
 * SkinToneStore / KeyboardFontSizeStore / PttModeStore.
 */
object ThemeStore {
    private const val PREFS_NAME = "ai_controller_theme"
    private const val KEY_THEME = "theme"

    fun load(context: Context): AppTheme =
        AppTheme.from(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_THEME, null)
        )

    fun save(context: Context, theme: AppTheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.name)
            .apply()
    }
}

/**
 * Applies the selected theme. Call this as the FIRST line of every activity's
 * onCreate (before super.onCreate) so setTheme takes effect. setDefaultNightMode
 * is a no-op when the value is unchanged, so calling it on every launch is safe.
 */
object ThemeManager {
    fun apply(activity: AppCompatActivity) {
        val theme = ThemeStore.load(activity)
        when (theme) {
            AppTheme.HIGH_CONTRAST -> activity.setTheme(R.style.Theme_AIController_HighContrast)
            AppTheme.GREEN_ON_BLACK -> activity.setTheme(R.style.Theme_AIController_GreenOnBlack)
            else -> Unit
        }
        AppCompatDelegate.setDefaultNightMode(theme.nightMode)
    }
}
