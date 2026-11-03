package io.github.elkelvinx.sashabridge.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.core.os.ConfigurationCompat
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The language the app shows its texts in, and dates written its way.
 *
 * Dates are asked for by their fields, a skeleton such as [DAY_HEADING], never by a written
 * pattern: Android then puts them in each language's own order and words ("lunes, 2 de
 * noviembre", "Monday, November 2", "11月2日星期一").
 *
 * Main thread.
 */
internal object LocaleFormats {

    /** Weekday, day and month in full: the history's day headings. */
    const val DAY_HEADING = "EEEEMMMMd"

    /** Short weekday, day and month: the bubble of the statistics' chart. */
    const val SHORT_DAY = "EEEMMMd"

    /** Day and short month: the first day under the statistics' chart. */
    const val DAY_MONTH = "MMMd"

    /** The app's language now: the one chosen in the settings, or else the phone's. */
    fun localeOf(context: Context): Locale =
        ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()

    /** A formatter of dates with the fields of [skeleton], the way [locale] writes them. */
    fun dates(skeleton: String, locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}
