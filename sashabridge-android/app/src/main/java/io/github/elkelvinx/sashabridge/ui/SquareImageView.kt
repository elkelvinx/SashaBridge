package io.github.elkelvinx.sashabridge.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.imageview.ShapeableImageView
import kotlin.math.min

/**
 * Image that is always square, so the album art can take whatever room the screen leaves.
 *
 * Its width sets the size, as between the skip buttons in portrait, unless only its height is
 * fixed, as in the landscape card, where the art is as tall as the space beside the track
 * details, up to `android:maxWidth`.
 *
 * Its size never depends on the picture's own pixels. The landscape screen scrolls, so it is
 * first measured with no height given; the art then asks for no room, and the screen, which
 * fits its content to the window, measures it again with the room left. Measured by the
 * picture instead, a 1000 px cover once stretched the card past the bottom of the screen.
 *
 * Main thread only.
 */
class SquareImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ShapeableImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        when {
            widthMode == MeasureSpec.EXACTLY -> super.onMeasure(widthMeasureSpec, widthMeasureSpec)
            heightMode == MeasureSpec.EXACTLY -> {
                val height = MeasureSpec.getSize(heightMeasureSpec)
                val room = if (widthMode == MeasureSpec.AT_MOST) min(height, MeasureSpec.getSize(widthMeasureSpec)) else height
                val exact = MeasureSpec.makeMeasureSpec(room.coerceAtMost(maxWidth), MeasureSpec.EXACTLY)
                super.onMeasure(exact, exact)
            }
            // Neither side given yet: no room of its own until the next pass gives it some.
            else -> setMeasuredDimension(suggestedMinimumWidth, suggestedMinimumHeight)
        }
    }
}
