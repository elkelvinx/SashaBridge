package io.github.elkelvinx.sashabridge.ui

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import io.github.elkelvinx.sashabridge.R
import kotlin.random.Random

/**
 * Sasha, the dachshund the app is named after, showing the state of the bridge.
 *
 * Everything about her lives here, so a new sprite only needs `tools/sasha_sprite.py` and this
 * class: the poses ([pose]), their animations, the hourglass she waits next to, and the drinks
 * she takes now and then while she plays. The view keeps the sprite's proportions (60 x 20
 * sprite pixels) in whatever size the layout gives it.
 *
 * Nothing runs while the view is not visible, and on Android 15 and later the animation asks
 * for a low screen refresh rate: she moves a few times a second, so the screen, often left on
 * as a display, can slow down instead of redrawing at 120 Hz.
 *
 * Main thread only.
 */
class SashaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** What Sasha is doing. */
    enum class Pose(@param:DrawableRes internal val drawable: Int) {
        /** Standing still, looking ahead. */
        AWAKE(R.drawable.sasha_awake),

        /** Wagging her tail next to a turning hourglass. */
        WAITING(R.drawable.sasha_wagging),

        /** Galloping after a tennis ball; now and then she stops to drink from her bucket. */
        PLAYING(R.drawable.sasha_running),

        /** Asleep. */
        SLEEPING(R.drawable.sasha_sleeping),
    }

    /** Pose Sasha shows; setting the same pose again does not restart her animation. */
    var pose: Pose = Pose.AWAKE
        set(value) {
            if (value == field) return
            field = value
            drinking = false
            show()
            scheduleDrink()
        }

    /** Whether she is taking one of her drinks, which she only does while [Pose.PLAYING]. */
    private var drinking = false

    private val sprite = ImageView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val hourglass = ImageView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.hourglass)
        isVisible = false
    }

    private val startDrink = Runnable {
        drinking = true
        show()
        postDelayed(endDrink, DRINK_MS)
    }

    private val endDrink = Runnable {
        drinking = false
        show()
        scheduleDrink()
    }

    init {
        addView(sprite, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hourglass, LayoutParams(0, 0, Gravity.END or Gravity.BOTTOM))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            sprite.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_LOW
            hourglass.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_LOW
        }
        show()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fittedWidth = fittedWidthSpec(widthMeasureSpec, heightMeasureSpec)
        super.onMeasure(fittedWidth, heightMeasureSpec)
        // The hourglass is drawn on the sprite's grid, in its bottom-right corner.
        val side = minOf(measuredWidth * HOURGLASS_PX / SPRITE_WIDTH_PX, measuredHeight * HOURGLASS_PX / SPRITE_HEIGHT_PX)
        val params = hourglass.layoutParams
        if (params.width != side) {
            params.width = side
            params.height = side
            super.onMeasure(fittedWidth, heightMeasureSpec)
        }
    }

    /**
     * Width for the sprite at the height given: as wide as its proportions ask, but never wider
     * than the room, so a narrow card shrinks Sasha whole instead of cutting off her ball.
     */
    private fun fittedWidthSpec(widthMeasureSpec: Int, heightMeasureSpec: Int): Int {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ||
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
        ) {
            return widthMeasureSpec
        }
        val byHeight = MeasureSpec.getSize(heightMeasureSpec) * SPRITE_WIDTH_PX / SPRITE_HEIGHT_PX
        return MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(widthMeasureSpec), byHeight), MeasureSpec.EXACTLY)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            scheduleDrink()
        } else {
            removeCallbacks(startDrink)
            removeCallbacks(endDrink)
            if (drinking) {
                drinking = false
                show()
            }
        }
    }

    /** Shows the current pose, or the drink, and starts its animations. */
    private fun show() {
        sprite.setImageResource(if (drinking) R.drawable.sasha_drinking else pose.drawable)
        (sprite.drawable as? AnimationDrawable)?.start()
        val waiting = pose == Pose.WAITING
        hourglass.isVisible = waiting
        (hourglass.drawable as? AnimationDrawable)?.let { animation ->
            animation.stop()
            if (waiting) {
                // Every wait starts with the sand at the top.
                animation.selectDrawable(0)
                animation.start()
            }
        }
    }

    /** While she plays and can be seen, plans her next drink in one or two minutes. */
    private fun scheduleDrink() {
        removeCallbacks(startDrink)
        removeCallbacks(endDrink)
        if (pose == Pose.PLAYING && isShown) {
            postDelayed(startDrink, Random.nextLong(DRINK_INTERVAL_MIN_MS, DRINK_INTERVAL_MAX_MS))
        }
    }

    private companion object {
        /** Size of the sprite and of the hourglass, in sprite pixels (see tools/sasha_sprite.py). */
        const val SPRITE_WIDTH_PX = 60
        const val SPRITE_HEIGHT_PX = 20
        const val HOURGLASS_PX = 11

        /** Sasha drinks once every 60 to 120 seconds while she plays, for about 4 seconds. */
        const val DRINK_INTERVAL_MIN_MS = 60_000L
        const val DRINK_INTERVAL_MAX_MS = 120_000L
        const val DRINK_MS = 4_000L
    }
}
