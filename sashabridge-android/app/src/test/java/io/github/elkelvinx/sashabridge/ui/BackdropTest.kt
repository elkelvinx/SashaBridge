package io.github.elkelvinx.sashabridge.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The blur of the player card's backdrop. */
class BackdropTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** A plain image stays the same, edges included. */
    @Test
    fun plainImagesDoNotChange() {
        val red = IntArray(16) { 0xFFE53935.toInt() }

        assertArrayEquals(red, Backdrop.blur(red, 4, 4, radius = 1, passes = 2))
    }

    /** A sharp edge becomes a gradient that keeps the order of the two sides. */
    @Test
    fun edgesBecomeGradients() {
        val width = 8
        val pixels = IntArray(width) { if (it < width / 2) black else white }

        val blurred = Backdrop.blur(pixels, width, 1, radius = 2, passes = 1)
        val reds = blurred.map { it shr 16 and 0xFF }

        assertEquals("the far left stays dark", 0, reds.first())
        assertEquals("the far right stays light", 255, reds.last())
        assertTrue("in between it only gets lighter", reds.zipWithNext().all { (a, b) -> a <= b })
        assertTrue("the middle is mixed", reds[width / 2] in 1..254)
    }

    /** Columns blur too, not only rows. */
    @Test
    fun columnsBlur() {
        val pixels = intArrayOf(black, black, white, white)

        val blurred = Backdrop.blur(pixels, 1, 4, radius = 1, passes = 1)

        assertTrue((blurred[1] shr 16 and 0xFF) in 1..254)
    }

    /** The blur level of the settings spreads over the radii, and stays in range. */
    @Test
    fun blurLevelsMapToRadii() {
        assertEquals(0, Backdrop.blurRadius(0))
        assertEquals(5, Backdrop.blurRadius(Backdrop.DEFAULT_LEVEL))
        assertEquals(9, Backdrop.blurRadius(Backdrop.MAX_LEVEL))
        assertEquals(9, Backdrop.blurRadius(500))
        assertEquals(0, Backdrop.blurRadius(-3))
    }

    /** A stronger backdrop means a thinner veil: full at 0, three quarters by default, half at most. */
    @Test
    fun strengthThinsTheVeil() {
        assertEquals(1f, Backdrop.veilAlpha(0), 0.001f)
        assertEquals(0.75f, Backdrop.veilAlpha(Backdrop.DEFAULT_LEVEL), 0.001f)
        assertEquals(0.5f, Backdrop.veilAlpha(Backdrop.MAX_LEVEL), 0.001f)
        assertEquals(0.5f, Backdrop.veilAlpha(200), 0.001f)
    }

    /** Grays stay gray, soft colors get stronger, and opacity does not change. */
    @Test
    fun saturateStrengthensColorsOnly() {
        val gray = 0xFF808080.toInt()
        val softRed = 0x80A07070.toInt()

        val (grayAfter, redAfter) = Backdrop.saturate(intArrayOf(gray, softRed), factor = 2f).toList()

        assertEquals(gray, grayAfter)
        assertEquals("opacity stays", 0x80, redAfter ushr 24)
        val red = redAfter shr 16 and 0xFF
        val green = redAfter shr 8 and 0xFF
        assertTrue("red moves up from 0xA0", red > 0xA0)
        assertTrue("green moves down from 0x70", green < 0x70)
    }

    /** Strong colors are clamped to the channel's range instead of wrapping around. */
    @Test
    fun saturateClamps() {
        val strong = 0xFFFF2000.toInt()

        val after = Backdrop.saturate(intArrayOf(strong), factor = 3f).single()

        assertEquals(0xFF, after shr 16 and 0xFF)
        assertEquals(0, after and 0xFF)
    }

    /** Opacity is blurred like the colors and stays opaque when everything is. */
    @Test
    fun opaqueStaysOpaque() {
        val pixels = intArrayOf(black, white, black, white)

        assertTrue(Backdrop.blur(pixels, 2, 2, radius = 1).all { it ushr 24 == 0xFF })
    }
}
