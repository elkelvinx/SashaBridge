package io.github.elkelvinx.sashabridge.settings

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.elkelvinx.sashabridge.R

/**
 * The languages the app speaks, as chosen in Ajustes > Apariencia.
 *
 * Android keeps the choice itself (the per-app language of Android 13 and later, which
 * AppCompat stores on older versions), so it is the same one Android's own settings show for
 * SashaBridge, and the app keeps no copy of it. Choosing one remakes the open screens in it.
 *
 * @property tag BCP 47 tag of the language, or `null` to follow the phone.
 * @property label How the option reads: each language in itself, so anyone finds theirs.
 */
enum class AppLanguage(val tag: String?, @StringRes val label: Int) {

    /** The phone's language, if the app has it; Spanish otherwise. */
    SYSTEM(null, R.string.language_system),
    SPANISH("es", R.string.language_spanish),
    ENGLISH("en", R.string.language_english),

    /** Mandarin in simplified characters, as written in mainland China. */
    CHINESE("zh-CN", R.string.language_chinese),
    ;

    /** Makes this the app's language; AppCompat remakes the open screens. Main thread. */
    fun apply() {
        val locales = tag?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList()
        AppCompatDelegate.setApplicationLocales(locales)
    }

    companion object {

        /** The language chosen now. Main thread. */
        fun current(): AppLanguage = of(AppCompatDelegate.getApplicationLocales().toLanguageTags())

        /**
         * The option for [tags], the app's languages as Android keeps them ("" when it follows
         * the phone). Only the first counts, and a region other than China's still reads as
         * its language. Pure.
         */
        fun of(tags: String): AppLanguage {
            val first = tags.substringBefore(',').trim()
            if (first.isEmpty()) return SYSTEM
            val language = first.substringBefore('-').lowercase()
            return entries.firstOrNull { it.tag?.substringBefore('-') == language } ?: SYSTEM
        }
    }
}
