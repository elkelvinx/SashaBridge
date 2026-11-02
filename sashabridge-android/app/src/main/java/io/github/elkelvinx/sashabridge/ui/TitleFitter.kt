package io.github.elkelvinx.sashabridge.ui

import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.widget.TextView

/**
 * Sizes the landscape card's title so it stays whole: as large as [MAX_SP] on one line,
 * shrinking down to [ONE_LINE_MIN_SP]; a longer title takes two lines from [TWO_LINES_MAX_SP]
 * down to [MIN_SP], and only past that ends with "...". The size changes only when it must,
 * so a track change does not reflow the card for nothing.
 *
 * Main thread.
 *
 * @param title The title; the layout starts it at [MAX_SP].
 */
internal class TitleFitter(private val title: TextView) {

    /** Size the title has now, in sp. */
    private var sizeSp = MAX_SP

    /** Sizes the title for its text and width; call once it is laid out. */
    fun fit() {
        val width = title.width - title.totalPaddingLeft - title.totalPaddingRight
        val text = title.text
        if (width <= 0 || text.isEmpty()) return
        val paint = TextPaint(title.paint)
        val metrics = title.resources.displayMetrics
        val size = sizeFor { sp ->
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build().lineCount
        }
        if (size != sizeSp) {
            sizeSp = size
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }
    }

    companion object {

        /** Sizes of a title that shrinks to fit: on one line, then on two, and the step. */
        const val MAX_SP = 30f
        const val ONE_LINE_MIN_SP = 22f
        const val TWO_LINES_MAX_SP = 24f
        const val MIN_SP = 18f
        const val STEP_SP = 2f

        /**
         * The size for a title that takes [lines] lines at each size in sp: the largest on one
         * line, else the largest on two, else [MIN_SP]. Pure.
         */
        fun sizeFor(lines: (Float) -> Int): Float {
            val oneLine = generateSequence(MAX_SP) { it - STEP_SP }
                .takeWhile { it >= ONE_LINE_MIN_SP }
                .firstOrNull { lines(it) == 1 }
            return oneLine ?: generateSequence(TWO_LINES_MAX_SP) { it - STEP_SP }
                .takeWhile { it > MIN_SP }
                .firstOrNull { lines(it) <= 2 } ?: MIN_SP
        }
    }
}
