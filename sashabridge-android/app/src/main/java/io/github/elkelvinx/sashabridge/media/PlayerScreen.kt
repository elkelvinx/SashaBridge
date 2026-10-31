package io.github.elkelvinx.sashabridge.media

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * Opens a player app on the screen its media session names ([android.media.session.MediaController.getSessionActivity]),
 * usually the one of the track or video playing; failing that, on its launcher screen.
 *
 * Android only lets the app open another one while one of its screens is in front. Main thread.
 */
internal object PlayerScreen {

    /**
     * Opens [packageName] on [screen], or on its launcher screen if there is none.
     *
     * @param context A screen of the app, or any context while one is in front.
     * @param screen The session's own screen, or `null`.
     * @return Whether some screen was asked to open.
     */
    fun open(context: Context, packageName: String, screen: PendingIntent?): Boolean {
        if (screen != null) {
            try {
                screen.send(context, 0, null, null, null, null, options())
                return true
            } catch (_: PendingIntent.CanceledException) {
                // The player dropped that screen; fall back to its launcher screen.
            }
        }
        // The launch intent already starts a new task, as a context without a screen needs.
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        context.startActivity(launch)
        return true
    }

    /**
     * Lets the player's own pending intent open its screen while one of this app's is visible,
     * which Android 14 and later require from the app that sends it.
     */
    private fun options(): Bundle? {
        val mode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                @Suppress("DEPRECATION")
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            else -> return null
        }
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }
}
