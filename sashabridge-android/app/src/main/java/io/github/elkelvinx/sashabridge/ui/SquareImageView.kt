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
 * details, up to `android:maxWidth`. Main thread only.
 */
class SquareImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ShapeableImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightOnly = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY &&
            MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY
        if (heightOnly) {
            val side = MeasureSpec.getSize(heightMeasureSpec).let { height ->
                if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
                    min(height, MeasureSpec.getSize(widthMeasureSpec))
                } else {
                    height
                }
            }.coerceAtMost(maxWidth)
            val exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
            super.onMeasure(exact, exact)
        } else {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }
}
