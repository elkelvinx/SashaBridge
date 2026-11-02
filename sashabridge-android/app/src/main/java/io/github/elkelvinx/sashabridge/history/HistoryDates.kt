package io.github.elkelvinx.sashabridge.history

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Days and months of the statistics as plain numbers, which sort and compare in SQL:
 * `20261031` for a day and `202610` for a month, in the phone's time zone.
 *
 * Pure and thread-safe.
 */
internal object HistoryDates {

    /** The month of [epochMs], such as `202610`. */
    fun month(epochMs: Long, zone: ZoneId): Int = month(YearMonth.from(Instant.ofEpochMilli(epochMs).atZone(zone)))

    /** [month] as a number, such as `202610`. */
    fun month(month: YearMonth): Int = month.year * MONTHS + month.monthValue

    /** The day of [epochMs], such as `20261031`. */
    fun day(epochMs: Long, zone: ZoneId): Int = day(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())

    /** [date] as a number, such as `20261031`. */
    fun day(date: LocalDate): Int = (date.year * MONTHS + date.monthValue) * DAYS + date.dayOfMonth

    /** The date of a day number, such as `20261031`. */
    fun date(day: Int): LocalDate = LocalDate.of(day / (MONTHS * DAYS), day / DAYS % MONTHS, day % DAYS)

    /** Multipliers that keep the parts apart: two digits for the month and for the day. */
    private const val MONTHS = 100
    private const val DAYS = 100
}
