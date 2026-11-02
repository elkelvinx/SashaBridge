package io.github.elkelvinx.sashabridge.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/** Times and shares of the statistics. */
class StatsFormatTest {

    /** Hours and the minutes left, rounded to the nearest minute. */
    @Test
    fun hoursAndMinutes() {
        assertEquals(0L to 0L, StatsFormat.hoursAndMinutes(0))
        assertEquals(0L to 0L, StatsFormat.hoursAndMinutes(29_999))
        assertEquals(0L to 1L, StatsFormat.hoursAndMinutes(30_000))
        assertEquals(0L to 45L, StatsFormat.hoursAndMinutes(45 * 60_000L))
        assertEquals(4L to 0L, StatsFormat.hoursAndMinutes((3 * 60 + 59) * 60_000L + 40_000))
        assertEquals(12L to 40L, StatsFormat.hoursAndMinutes((12 * 60 + 40) * 60_000L))
        assertEquals(0L to 0L, StatsFormat.hoursAndMinutes(-5_000))
    }

    /** A share of the whole, kept in range. */
    @Test
    fun share() {
        assertEquals(500, StatsFormat.share(50, 100, 1000))
        assertEquals(1000, StatsFormat.share(100, 100, 1000))
        assertEquals(1000, StatsFormat.share(150, 100, 1000))
        assertEquals(0, StatsFormat.share(5, 0, 1000))
    }
}
