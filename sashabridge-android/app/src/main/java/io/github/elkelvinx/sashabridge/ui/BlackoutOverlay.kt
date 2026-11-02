package io.github.elkelvinx.sashabridge.ui

import android.app.Activity
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import io.github.elkelvinx.sashabridge.R

/**
 * The black cover of the "Pantalla negra" way of hiding a video: black over everything, no
 * system bars and the lowest brightness, as if the phone were off. The screen still turns off
 * on its own. A double tap asks to uncover the app ([onUncover]).
 *
 * When to cover is the screen's decision; this class only covers and uncovers.
 *
 * Main thread.
 *
 * @param activity The screen to cover.
 * @param displayMode Puts the system bars back as the screen wants them when uncovering.
 * @param onUncover Called on a double tap on the cover.
 */
internal class BlackoutOverlay(
    private val activity: Activity,
    private val displayMode: DisplayMode,
    private val onUncover: () -> Unit,
) {

    /** The cover, made on first use. */
    private var cover: View? = null

    /** Whether the cover is on. */
    var covering = false
        private set

    /**
     * Covers the screen, or uncovers it.
     *
     * @return Whether anything changed.
     */
    fun show(on: Boolean): Boolean {
        if (on == covering) return false
        covering = on
        val view = cover ?: create().also { cover = it }
        view.isVisible = on
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = if (on) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (on) {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        } else {
            displayMode.applyBars()
        }
        return true
    }

    /** Makes the cover, over the whole window; every touch stays on it. */
    private fun create(): View {
        val doubleTap = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onUncover()
                return true
            }
        })
        return View(activity).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = activity.getString(R.string.blackout_description)
            isVisible = false
            // Every touch stays on the cover, so nothing underneath reacts.
            setOnTouchListener { view, event ->
                doubleTap.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP) view.performClick()
                true
            }
            activity.addContentView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
}
