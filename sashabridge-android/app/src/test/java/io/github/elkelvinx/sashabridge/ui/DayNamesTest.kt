package io.github.elkelvinx.sashabridge.ui

import io.github.elkelvinx.sashabridge.ui.DayNames.Relative
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which days are "Hoy" and "Ayer". */
class DayNamesTest {

    private val today = LocalDate.of(2026, 11, 2)

    @Test
    fun todayYesterdayAndOtherDays() {
        assertEquals(Relative.TODAY, DayNames.relative(today, today))
        assertEquals(Relative.YESTERDAY, DayNames.relative(LocalDate.of(2026, 11, 1), today))
        assertEquals(Relative.OTHER, DayNames.relative(LocalDate.of(2026, 10, 31), today))
        // Tomorrow, after midnight passes on a screen left open, is just another date.
        assertEquals(Relative.OTHER, DayNames.relative(LocalDate.of(2026, 11, 3), today))
    }

    /** Across the end of a month and a year. */
    @Test
    fun yesterdayAcrossMonthsAndYears() {
        assertEquals(Relative.YESTERDAY, DayNames.relative(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)))
    }
}
