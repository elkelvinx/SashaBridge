package io.github.elkelvinx.sashabridge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Times next to the seek bar. */
class TrackTimeTest {

    /** Minutes and seconds always have two digits, like the mock-up's "00:12" and "05:17". */
    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("00:00", TrackTime.format(0))
        assertEquals("00:12", TrackTime.format(12_999))
        assertEquals("05:17", TrackTime.format(317_000))
    }

    /** Long tracks, such as podcasts, show the hours. */
    @Test
    fun formatsHours() {
        assertEquals("1:02:03", TrackTime.format(3_723_000))
    }

    /** A negative time is shown as zero. */
    @Test
    fun negativeTimeIsZero() {
        assertEquals("00:00", TrackTime.format(-5))
    }
}
