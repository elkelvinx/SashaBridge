package io.github.elkelvinx.sashabridge.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.imageview.ShapeableImageView

/**
 * Image that is always as tall as it is wide, so the album art can take whatever width the
 * screen leaves between the skip buttons. Main thread only.
 */
class SquareImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ShapeableImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
