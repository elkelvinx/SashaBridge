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

    /** Opacity is blurred like the colors and stays opaque when everything is. */
    @Test
    fun opaqueStaysOpaque() {
        val pixels = intArrayOf(black, white, black, white)

        assertTrue(Backdrop.blur(pixels, 2, 2).all { it ushr 24 == 0xFF })
    }
}
