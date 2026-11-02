package io.github.elkelvinx.sashabridge.history

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * Names and icons of the apps the history and the statistics show, found once per app.
 * An app Android does not let this one see shows its package and no icon.
 *
 * Main thread.
 */
internal class AppNames(private val packageManager: PackageManager) {

    private val labels = HashMap<String, String>()
    private val icons = HashMap<String, Drawable?>()

    /** The name people know [packageName] by, such as "Spotify", or the package itself. */
    fun label(packageName: String): String = labels.getOrPut(packageName) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    /** The launcher icon of [packageName], or `null`. */
    fun icon(packageName: String): Drawable? = icons.getOrPut(packageName) {
        try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
