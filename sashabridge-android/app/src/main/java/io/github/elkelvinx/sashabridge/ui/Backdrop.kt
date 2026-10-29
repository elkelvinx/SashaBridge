package io.github.elkelvinx.sashabridge.ui

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

/**
 * The blurred copy of the album art behind the player card.
 *
 * The blur is computed once per track, on a tiny copy of the art, and the result is a plain
 * bitmap. A blur effect on the view would instead be recomputed by the GPU on every frame the
 * card redraws, which matters when the screen stays on as a display.
 *
 * Pure apart from the bitmap calls; thread-safe.
 */
object Backdrop {

    /** Side of the tiny copy that is blurred. */
    private const val SMALL_PX = 32

    /** Radius of each box blur pass, in pixels of the tiny copy, and the number of passes. */
    private const val RADIUS = 3
    private const val PASSES = 3

    /** Side of the bitmap the view stretches; upscaling it with filtering smooths the result. */
    private const val SHOWN_PX = 128

    /**
     * Returns a heavily blurred copy of [art], ready to stretch over the card.
     *
     * @param art The album art.
     * @return A new [SHOWN_PX]-pixel square bitmap.
     */
    fun of(art: Bitmap): Bitmap {
        val small = art.scale(SMALL_PX, SMALL_PX)
        val pixels = IntArray(SMALL_PX * SMALL_PX)
        small.getPixels(pixels, 0, SMALL_PX, 0, 0, SMALL_PX, SMALL_PX)
        val blurred = createBitmap(SMALL_PX, SMALL_PX)
        blurred.setPixels(blur(pixels, SMALL_PX, SMALL_PX), 0, SMALL_PX, 0, 0, SMALL_PX, SMALL_PX)
        return blurred.scale(SHOWN_PX, SHOWN_PX)
    }

    /**
     * Blurs ARGB [pixels] of a [width] x [height] image with repeated box blurs, which
     * approach a Gaussian blur. The edges repeat outwards.
     *
     * @return A new array with the blurred pixels.
     */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int = RADIUS, passes: Int = PASSES): IntArray {
        var current = pixels.copyOf()
        repeat(passes) {
            current = pass(current, width, height, radius, horizontal = true)
            current = pass(current, width, height, radius, horizontal = false)
        }
        return current
    }

    /** One box blur pass along rows ([horizontal]) or columns. */
    private fun pass(source: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean): IntArray {
        val out = IntArray(source.size)
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            val at = { i: Int ->
                val clamped = i.coerceIn(0, length - 1)
                if (horizontal) line * width + clamped else clamped * width + line
            }
            for (i in 0 until length) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (k in -radius..radius) {
                    val pixel = source[at(i + k)]
                    a += pixel ushr ALPHA_SHIFT
                    r += pixel shr RED_SHIFT and CHANNEL
                    g += pixel shr GREEN_SHIFT and CHANNEL
                    b += pixel and CHANNEL
                }
                out[at(i)] = (a / window shl ALPHA_SHIFT) or (r / window shl RED_SHIFT) or (g / window shl GREEN_SHIFT) or (b / window)
            }
        }
        return out
    }

    private const val CHANNEL = 0xFF
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
