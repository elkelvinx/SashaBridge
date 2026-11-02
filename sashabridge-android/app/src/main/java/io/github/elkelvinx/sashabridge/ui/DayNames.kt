package io.github.elkelvinx.sashabridge.ui

import android.content.Context
import io.github.elkelvinx.sashabridge.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the app names a day: "Hoy", "Ayer", or the date, starting with a capital, as the
 * history's headings and the statistics' bubbles show it.
 *
 * Main thread.
 *
 * @param context Gives the words for today and yesterday, in the app's language.
 * @param zone Where today starts.
 */
internal class DayNames(private val context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {

    /** The name of [date], with any other date written in [format]. */
    fun of(date: LocalDate, format: DateTimeFormatter): String = when (relative(date, LocalDate.now(zone))) {
        Relative.TODAY -> context.getString(R.string.history_today)
        Relative.YESTERDAY -> context.getString(R.string.history_yesterday)
        Relative.OTHER -> format.format(date).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    /** Where [date] falls next to [today]. */
    enum class Relative { TODAY, YESTERDAY, OTHER }

    companion object {

        /** Where [date] falls next to [today]. Pure. */
        fun relative(date: LocalDate, today: LocalDate): Relative = when (date) {
            today -> Relative.TODAY
            today.minusDays(1) -> Relative.YESTERDAY
            else -> Relative.OTHER
        }
    }
}
