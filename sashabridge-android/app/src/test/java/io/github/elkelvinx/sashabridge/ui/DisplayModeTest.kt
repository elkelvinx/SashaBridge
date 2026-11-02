package io.github.elkelvinx.sashabridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the screen stays on, and how the OLED shift moves. */
class DisplayModeTest {

    /** Only as a display, charging, allowed by the settings and not under the black cover. */
    @Test
    fun keepsTheScreenOnOnlyWhenEverythingAgrees() {
        assertTrue(DisplayMode.keepsScreenOn(display = true, charging = true, allowed = true, covered = false))
        assertFalse(DisplayMode.keepsScreenOn(display = false, charging = true, allowed = true, covered = false))
        assertFalse(DisplayMode.keepsScreenOn(display = true, charging = false, allowed = true, covered = false))
        assertFalse(DisplayMode.keepsScreenOn(display = true, charging = true, allowed = false, covered = false))
        assertFalse(DisplayMode.keepsScreenOn(display = true, charging = true, allowed = true, covered = true))
    }

    /** The shift visits every position around the start, one step at most, and comes back. */
    @Test
    fun theShiftStaysWithinOneStep() {
        assertEquals(0 to 0, DisplayMode.SHIFTS.first())
        assertTrue(DisplayMode.SHIFTS.all { (x, y) -> x in -1..1 && y in -1..1 })
        assertEquals(DisplayMode.SHIFTS.size, DisplayMode.SHIFTS.toSet().size)
    }
}
