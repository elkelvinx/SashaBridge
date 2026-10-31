package io.github.elkelvinx.sashabridge.ui

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.math.roundToInt

/**
 * The blurred copy of the album art behind the player card.
 *
 * The blur is computed once per track, on a tiny copy of the art, and the result is a plain
 * bitmap. A blur effect on the view would instead be recomputed by the GPU on every frame the
 * card redraws, which matters when the screen stays on as a display.
 *
 * Blurring mixes the colors of the art toward gray, and art of soft colors almost vanished
 * under the card's veil, so the colors are made stronger afterwards ([saturate]).
 *
 * The user sets how blurred the art is ([blurRadius]) and how strongly it shows through the
 * veil ([veilAlpha]), both from 0 to 100, in the settings.
 *
 * Pure apart from the bitmap calls; thread-safe.
 */
object Backdrop {

    /** Side of the tiny copy that is blurred. */
    private const val SMALL_PX = 48

    /** Radius of the box blur passes at the strongest blur, in pixels of the tiny copy. */
    private const val MAX_RADIUS = 9

    /** Number of box blur passes, which together approach a Gaussian blur. */
    private const val PASSES = 3

    /** Levels of the settings, from 0 to [MAX_LEVEL]; [DEFAULT_LEVEL] until the user moves them. */
    const val MAX_LEVEL = 100
    const val DEFAULT_LEVEL = 50

    /** How much the veil thins between the subtlest and the strongest backdrop. */
    private const val VEIL_RANGE = 0.5f

    /** Side of the bitmap the view stretches; upscaling it with filtering smooths the result. */
    private const val SHOWN_PX = 128

    /** How much stronger the colors get after the blur; 1 would keep them. */
    private const val SATURATION = 1.45f

    /**
     * Returns a blurred copy of [art], ready to stretch over the card.
     *
     * @param art The album art.
     * @param blur How blurred, from 0 (only softened by the small size) to [MAX_LEVEL].
     * @return A new [SHOWN_PX]-pixel square bitmap.
     */
    fun of(art: Bitmap, blur: Int = DEFAULT_LEVEL): Bitmap {
        val small = art.scale(SMALL_PX, SMALL_PX)
        val pixels = IntArray(SMALL_PX * SMALL_PX)
        small.getPixels(pixels, 0, SMALL_PX, 0, 0, SMALL_PX, SMALL_PX)
        val blurred = createBitmap(SMALL_PX, SMALL_PX)
        val softened = blur(pixels, SMALL_PX, SMALL_PX, radius = blurRadius(blur))
        blurred.setPixels(saturate(softened), 0, SMALL_PX, 0, 0, SMALL_PX, SMALL_PX)
        return blurred.scale(SHOWN_PX, SHOWN_PX)
    }

    /** Radius of the blur passes for the blur level [level], from 0 to [MAX_RADIUS]. */
    fun blurRadius(level: Int): Int = (level.coerceIn(0, MAX_LEVEL) * MAX_RADIUS + MAX_LEVEL / 2) / MAX_LEVEL

    /**
     * Opacity of the card's veil for the backdrop strength [level]: the full veil at 0, half of
     * it at [MAX_LEVEL]. The veil drawable holds the strongest veil.
     */
    fun veilAlpha(level: Int): Float = 1f - VEIL_RANGE * level.coerceIn(0, MAX_LEVEL) / MAX_LEVEL
    /**
     * Blurs ARGB [pixels] of a [width] x [height] image with repeated box blurs, which
     * approach a Gaussian blur. The edges repeat outwards.
     *
     * @return A new array with the blurred pixels.
     */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = PASSES): IntArray {
        var current = pixels.copyOf()
        repeat(passes) {
            current = pass(current, width, height, radius, horizontal = true)
            current = pass(current, width, height, radius, horizontal = false)
        }
        return current
    }

    /**
     * Moves each color of ARGB [pixels] away from its own gray by [factor]: grays stay, and
     * soft colors get stronger. Opacity does not change.
     *
     * @return A new array with the stronger colors.
     */
    fun saturate(pixels: IntArray, factor: Float = SATURATION): IntArray = IntArray(pixels.size) { index ->
        val pixel = pixels[index]
        val red = pixel shr RED_SHIFT and CHANNEL
        val green = pixel shr GREEN_SHIFT and CHANNEL
        val blue = pixel and CHANNEL
        // Luma of Rec. 601: the gray the eye sees for this color.
        val gray = (red * LUMA_RED + green * LUMA_GREEN + blue * LUMA_BLUE) / LUMA_TOTAL.toFloat()
        val stronger = { channel: Int -> (gray + (channel - gray) * factor).roundToInt().coerceIn(0, CHANNEL) }
        (pixel and ALPHA_MASK) or (stronger(red) shl RED_SHIFT) or (stronger(green) shl GREEN_SHIFT) or stronger(blue)
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
    private const val ALPHA_MASK = 0xFF shl 24
    private const val LUMA_RED = 299
    private const val LUMA_GREEN = 587
    private const val LUMA_BLUE = 114
    private const val LUMA_TOTAL = 1_000
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
