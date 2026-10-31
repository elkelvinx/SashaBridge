package io.github.elkelvinx.sashabridge.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.elkelvinx.sashabridge.ui.Backdrop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the phone looks while a video is hidden with the PC's Ctrl+Alt+O. */
enum class HideStyle {

    /** SashaBridge in front, with the last music track paused. */
    MUSIC,

    /** A black screen, as if the phone were off; a double tap shows SashaBridge. */
    BLACKOUT,
}

/**
 * The user's choices on the settings screen ([SettingsActivity]), kept across restarts.
 *
 * Each choice is a [StateFlow], so the service and the screens follow a change at once.
 * One instance per process, obtained with [get]. Every member is thread-safe.
 *
 * @param preferences Where the choices are kept.
 */
class SettingsRepository private constructor(private val preferences: SharedPreferences) {

    private val mutableHideStyle = MutableStateFlow(stored(KEY_HIDE_STYLE, HideStyle.MUSIC))
    private val mutableBlackoutInPortrait = MutableStateFlow(preferences.getBoolean(KEY_BLACKOUT_IN_PORTRAIT, true))
    private val mutableReturnToVideo = MutableStateFlow(preferences.getBoolean(KEY_RETURN_TO_VIDEO, true))
    private val mutableKeepScreenOn = MutableStateFlow(preferences.getBoolean(KEY_KEEP_SCREEN_ON, true))
    private val mutableRecordHistory = MutableStateFlow(preferences.getBoolean(KEY_RECORD_HISTORY, true))
    private val mutableTheme = MutableStateFlow(stored(KEY_THEME, ThemeMode.SYSTEM))
    private val mutableAccent = MutableStateFlow(stored(KEY_ACCENT, Accent.WINE))
    private val mutableWaves = MutableStateFlow(preferences.getBoolean(KEY_WAVES, true))
    private val mutableBackdropBlur = MutableStateFlow(preferences.getInt(KEY_BACKDROP_BLUR, Backdrop.DEFAULT_LEVEL))
    private val mutableBackdropStrength = MutableStateFlow(preferences.getInt(KEY_BACKDROP_STRENGTH, Backdrop.DEFAULT_LEVEL))

    /** How the phone looks while a video is hidden; [HideStyle.MUSIC] until chosen. */
    val hideStyle: StateFlow<HideStyle> = mutableHideStyle.asStateFlow()

    /** Whether the black screen of [HideStyle.BLACKOUT] covers portrait too, not only landscape. */
    val blackoutInPortrait: StateFlow<Boolean> = mutableBlackoutInPortrait.asStateFlow()

    /** Whether pressing Ctrl+Alt+O again goes back to the hidden video. */
    val returnToVideo: StateFlow<Boolean> = mutableReturnToVideo.asStateFlow()

    /** Whether the landscape screen stays on while the phone charges. */
    val keepScreenOn: StateFlow<Boolean> = mutableKeepScreenOn.asStateFlow()

    /** Whether the tracks listened to go to the history. */
    val recordHistory: StateFlow<Boolean> = mutableRecordHistory.asStateFlow()

    /** Light or dark theme; [ThemeMode.SYSTEM] until chosen. */
    val theme: StateFlow<ThemeMode> = mutableTheme.asStateFlow()

    /** Accent color; [Accent.WINE] until chosen. */
    val accent: StateFlow<Accent> = mutableAccent.asStateFlow()

    /** Whether the played part of the seek bar is a wave rather than a straight line. */
    val waves: StateFlow<Boolean> = mutableWaves.asStateFlow()

    /** How blurred the art behind the player card is, from 0 to [Backdrop.MAX_LEVEL]. */
    val backdropBlur: StateFlow<Int> = mutableBackdropBlur.asStateFlow()

    /** How strongly the art behind the player card shows through its veil, from 0 to [Backdrop.MAX_LEVEL]. */
    val backdropStrength: StateFlow<Int> = mutableBackdropStrength.asStateFlow()

    /** Chooses how the phone looks while a video is hidden. */
    fun setHideStyle(style: HideStyle) {
        preferences.edit { putString(KEY_HIDE_STYLE, style.name) }
        mutableHideStyle.value = style
    }

    /** Chooses whether the black screen covers portrait too. */
    fun setBlackoutInPortrait(value: Boolean) {
        preferences.edit { putBoolean(KEY_BLACKOUT_IN_PORTRAIT, value) }
        mutableBlackoutInPortrait.value = value
    }

    /** Chooses whether pressing Ctrl+Alt+O again goes back to the hidden video. */
    fun setReturnToVideo(value: Boolean) {
        preferences.edit { putBoolean(KEY_RETURN_TO_VIDEO, value) }
        mutableReturnToVideo.value = value
    }

    /** Chooses whether the landscape screen stays on while the phone charges. */
    fun setKeepScreenOn(value: Boolean) {
        preferences.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }
        mutableKeepScreenOn.value = value
    }

    /** Chooses whether the tracks listened to go to the history. */
    fun setRecordHistory(value: Boolean) {
        preferences.edit { putBoolean(KEY_RECORD_HISTORY, value) }
        mutableRecordHistory.value = value
    }

    /** Chooses the light or dark theme; the caller applies it ([Appearance.applyTheme]). */
    fun setTheme(mode: ThemeMode) {
        preferences.edit { putString(KEY_THEME, mode.name) }
        mutableTheme.value = mode
    }

    /** Chooses the accent color; open screens show it once recreated. */
    fun setAccent(accent: Accent) {
        preferences.edit { putString(KEY_ACCENT, accent.name) }
        mutableAccent.value = accent
    }

    /** Chooses whether the played part of the seek bar is a wave. */
    fun setWaves(value: Boolean) {
        preferences.edit { putBoolean(KEY_WAVES, value) }
        mutableWaves.value = value
    }

    /** Chooses how blurred the art behind the player card is; kept between 0 and [Backdrop.MAX_LEVEL]. */
    fun setBackdropBlur(level: Int) {
        val value = level.coerceIn(0, Backdrop.MAX_LEVEL)
        preferences.edit { putInt(KEY_BACKDROP_BLUR, value) }
        mutableBackdropBlur.value = value
    }

    /** Chooses how strongly that art shows; kept between 0 and [Backdrop.MAX_LEVEL]. */
    fun setBackdropStrength(level: Int) {
        val value = level.coerceIn(0, Backdrop.MAX_LEVEL)
        preferences.edit { putInt(KEY_BACKDROP_STRENGTH, value) }
        mutableBackdropStrength.value = value
    }

    /** The choice kept under [key], or [default] if there is none or it is not a value of [T] any more. */
    private inline fun <reified T : Enum<T>> stored(key: String, default: T): T {
        val name = preferences.getString(key, null)
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }

    companion object {

        /** Name of the preferences file. */
        private const val PREFERENCES = "settings"

        private const val KEY_HIDE_STYLE = "hide_style"
        private const val KEY_BLACKOUT_IN_PORTRAIT = "blackout_in_portrait"
        private const val KEY_THEME = "theme"
        private const val KEY_ACCENT = "accent"
        private const val KEY_WAVES = "waves"
        private const val KEY_BACKDROP_BLUR = "backdrop_blur"
        private const val KEY_BACKDROP_STRENGTH = "backdrop_strength"
        private const val KEY_RETURN_TO_VIDEO = "return_to_video"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_RECORD_HISTORY = "record_history"

        @Volatile
        private var instance: SettingsRepository? = null

        /**
         * Returns the process-wide instance, creating it on first use. Any thread.
         *
         * @param context Any context; only its application context is kept.
         */
        fun get(context: Context): SettingsRepository = instance ?: synchronized(this) {
            instance ?: SettingsRepository(
                context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
