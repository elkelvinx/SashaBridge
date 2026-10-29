package io.github.elkelvinx.sashabridge.pen

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * Asks Android to deliver pointer events to this view as soon as they arrive.
 *
 * By default Android batches input once per screen frame, and the screen may slow its frame
 * rate when little moves, so pen samples would reach the view late and in bursts. Drawing
 * views call this when attached (Android 11 and later: every pointer event, hovering
 * included) and with each [MotionEvent.ACTION_DOWN] (Android 10: the gesture that starts).
 *
 * Main thread only.
 *
 * @param down The [MotionEvent.ACTION_DOWN] that starts a gesture, or `null` when attaching.
 */
internal fun View.requestImmediatePen(down: MotionEvent? = null) {
    if (down != null) {
        requestUnbufferedDispatch(down)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
    }
}
