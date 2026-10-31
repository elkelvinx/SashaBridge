package io.github.elkelvinx.sashabridge.settings

import android.app.Activity
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import io.github.elkelvinx.sashabridge.R

/** Light or dark theme, as chosen in Ajustes > Apariencia. */
enum class ThemeMode(val nightMode: Int) {

    /** Follows the phone. */
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),

    /** Always light. */
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),

    /** Always dark. */
    DARK(AppCompatDelegate.MODE_NIGHT_YES),
}

/**
 * Accent color of the app, as chosen in Ajustes > Apariencia: the play button, the seek bar,
 * the active modes, the liked heart and the switches.
 *
 * @property overlay Theme overlay that sets the accent's shades.
 * @property swatch Color of its circle in the settings.
 * @property label Its name.
 */
enum class Accent(@StyleRes val overlay: Int, @ColorRes val swatch: Int, @StringRes val label: Int) {
    WINE(R.style.ThemeOverlay_SashaBridge_Accent_Wine, R.color.seek_thumb, R.string.accent_wine),
    BLUE(R.style.ThemeOverlay_SashaBridge_Accent_Blue, R.color.accent_blue_strong, R.string.accent_blue),
    GREEN(R.style.ThemeOverlay_SashaBridge_Accent_Green, R.color.accent_green_strong, R.string.accent_green),
    PURPLE(R.style.ThemeOverlay_SashaBridge_Accent_Purple, R.color.accent_purple_strong, R.string.accent_purple),
    AMBER(R.style.ThemeOverlay_SashaBridge_Accent_Amber, R.color.accent_amber_strong, R.string.accent_amber),
    PINK(R.style.ThemeOverlay_SashaBridge_Accent_Pink, R.color.accent_pink_strong, R.string.accent_pink),
}

/** Applies the appearance the user chose. Main thread. */
object Appearance {

    /** Sets [mode] for the whole app; AppCompat recreates the open screens that need it. */
    fun applyTheme(mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    /**
     * Lays [accent] over [activity]'s theme. Call in `onCreate`, before the layout is inflated;
     * views inflated before keep the accent they had.
     *
     * @param accent The accent; the one chosen in the settings unless given.
     * @return The accent applied.
     */
    fun applyAccent(activity: Activity, accent: Accent = SettingsRepository.get(activity).accent.value): Accent {
        activity.theme.applyStyle(accent.overlay, true)
        return accent
    }
}
