package io.github.elkelvinx.sashabridge.settings

import android.content.SharedPreferences
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import io.github.elkelvinx.sashabridge.R

/**
 * A "Vista previa" row of the settings that unfolds an example, and folds it away again.
 *
 * The example is only drawn while it is open ([onOpen] runs each time it opens), so a folded
 * one costs nothing. Whether it is open is remembered for the next visit.
 *
 * Main thread.
 *
 * @param root The settings' content, which moves smoothly as the example unfolds.
 * @param header The touchable row.
 * @param chevron Its arrow, pointing down while folded and up while open.
 * @param content The example.
 * @param memory Where the open state is remembered.
 * @param key Name of this example in [memory].
 * @param onOpen Draws the example; called on every opening, including at start if it was open.
 */
internal class PreviewSection(
    private val root: ViewGroup,
    private val header: View,
    private val chevron: View,
    private val content: View,
    private val memory: SharedPreferences,
    private val key: String,
    private val onOpen: () -> Unit,
) {

    /** Whether the example shows. */
    var isOpen: Boolean = memory.getBoolean(key, false)
        private set

    init {
        show(animate = false)
        header.setOnClickListener {
            isOpen = !isOpen
            memory.edit { putBoolean(key, isOpen) }
            show(animate = true)
        }
    }

    /** Folds or unfolds the example as [isOpen] says. */
    private fun show(animate: Boolean) {
        if (animate) TransitionManager.beginDelayedTransition(root)
        content.isVisible = isOpen
        val rotation = if (isOpen) OPEN_ROTATION else 0f
        if (animate) chevron.animate().rotation(rotation).setDuration(TURN_MS).start() else chevron.rotation = rotation
        ViewCompat.setStateDescription(
            header,
            header.context.getString(if (isOpen) R.string.settings_preview_open else R.string.settings_preview_closed),
        )
        if (isOpen) onOpen()
    }

    private companion object {
        const val OPEN_ROTATION = 180f
        const val TURN_MS = 200L
    }
}
