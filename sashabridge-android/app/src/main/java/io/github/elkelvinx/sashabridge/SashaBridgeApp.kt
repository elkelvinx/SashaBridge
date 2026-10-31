package io.github.elkelvinx.sashabridge

import android.app.Application
import io.github.elkelvinx.sashabridge.settings.Appearance
import io.github.elkelvinx.sashabridge.settings.SettingsRepository

/**
 * The app's process. Before any screen opens, it applies the light or dark theme chosen in
 * the settings, so no screen shows in the wrong one first.
 */
class SashaBridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Appearance.applyTheme(SettingsRepository.get(this).theme.value)
    }
}
