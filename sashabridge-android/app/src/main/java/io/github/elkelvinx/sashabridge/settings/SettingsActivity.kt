package io.github.elkelvinx.sashabridge.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.history.HistoryRepository
import io.github.elkelvinx.sashabridge.ui.WavyProgressDrawable
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The settings, opened from the main screen in portrait:
 * - **Apariencia:** theme, primary color and seek bar, with an example of the controls.
 * - **Fondo de la tarjeta:** blur and strength of the art behind the player card, with an
 *   example card.
 * - How a video hidden from the PC looks, and whether the shortcut goes back to it.
 * - The landscape display, and the history.
 *
 * Every choice is saved at once in [SettingsRepository]; there is nothing to confirm. A new
 * primary color redraws this screen in place ([render]) at once; a new theme makes it again,
 * as AppCompat does for every open screen.
 *
 * Main thread.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository

    /** Open state of the examples, which belongs to this screen only. */
    private val previews: SharedPreferences by lazy { getPreferences(Context.MODE_PRIVATE) }

    /** Wave of the color example's seek bar, once drawn; it follows the seek bar choice. */
    private var exampleWave: WavyProgressDrawable? = null

    /** Example of the card's background, once drawn. */
    private var backdropPreview: BackdropPreview? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Appearance.applyAccent(this)
        enableEdgeToEdge()
        settings = SettingsRepository.get(this)
        render()
    }

    /**
     * Inflates the screen with the current theme and binds every control to its setting.
     * Called again, in place, when the primary color changes.
     */
    private fun render() {
        setContentView(R.layout.activity_settings)
        val root = findViewById<View>(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        findViewById<MaterialToolbar>(R.id.settingsToolbar).setNavigationOnClickListener { finish() }

        bindTheme()
        bindAccents()
        bindToggle(R.id.progressGroup, if (settings.waves.value) R.id.progressWaves else R.id.progressLine) { id ->
            settings.setWaves(id == R.id.progressWaves)
            exampleWave?.setWaves(settings.waves.value)
        }
        bindAccentPreview()
        bindBackdrop()
        bindHiding()
        bindSwitch(R.id.keepScreenOnSwitch, settings.keepScreenOn.value, settings::setKeepScreenOn)
        bindSwitch(R.id.recordHistorySwitch, settings.recordHistory.value, settings::setRecordHistory)
        findViewById<Button>(R.id.clearHistoryButton).setOnClickListener { confirmClearHistory() }
        findViewById<TextView>(R.id.settingsVersion).text = getString(R.string.settings_version, versionName())
    }

    /** Light, dark or as the phone; AppCompat makes the open screens again with the new theme. */
    private fun bindTheme() {
        val ids = mapOf(ThemeMode.SYSTEM to R.id.themeSystem, ThemeMode.LIGHT to R.id.themeLight, ThemeMode.DARK to R.id.themeDark)
        bindToggle(R.id.themeGroup, ids.getValue(settings.theme.value)) { id ->
            val mode = ids.entries.first { it.value == id }.key
            if (mode == settings.theme.value) return@bindToggle
            settings.setTheme(mode)
            Appearance.applyTheme(mode)
        }
    }

    /**
     * One round swatch per primary color, with a check mark on the chosen one and its name
     * below. Choosing another redraws the screen in it at once, keeping the scroll position;
     * the main screen follows when it shows again.
     */
    private fun bindAccents() {
        val chosen = settings.accent.value
        findViewById<TextView>(R.id.accentName).setText(chosen.label)
        val row = findViewById<LinearLayout>(R.id.accentSwatches)
        for (accent in Accent.entries) {
            val swatch = layoutInflater.inflate(R.layout.item_accent_swatch, row, false) as ImageButton
            swatch.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, accent.swatch))
            swatch.contentDescription = getString(R.string.settings_accent_description, getString(accent.label))
            swatch.isSelected = accent == chosen
            if (accent == chosen) swatch.setImageResource(R.drawable.ic_check)
            swatch.setOnClickListener { chooseAccent(accent) }
            row.addView(swatch)
        }
    }

    /** Saves [accent] and redraws the screen with it, where it was scrolled to. */
    private fun chooseAccent(accent: Accent) {
        if (accent == settings.accent.value) return
        settings.setAccent(accent)
        Appearance.applyAccent(this, accent)
        val scrolled = findViewById<ScrollView>(R.id.settingsScroll).scrollY
        render()
        findViewById<ScrollView>(R.id.settingsScroll).doOnLayout { it.scrollTo(0, scrolled) }
    }

    /** The color example: the player's controls and a switch in the chosen color. */
    private fun bindAccentPreview() {
        exampleWave = null
        val example = findViewById<View>(R.id.accentPreview)
        PreviewSection(
            root = findViewById(R.id.settingsContent),
            header = findViewById(R.id.accentPreviewHeader),
            chevron = findViewById(R.id.accentPreviewChevron),
            content = example,
            memory = previews,
            key = KEY_ACCENT_PREVIEW,
        ) {
            if (exampleWave != null) return@PreviewSection
            val wave = WavyProgressDrawable(this).apply {
                setWaves(settings.waves.value)
                setPlaying(true)
            }
            example.findViewById<SeekBar>(R.id.previewSeek).apply {
                progressDrawable = wave
                // Set after the drawable, which only learns the progress when it changes.
                progress = EXAMPLE_PROGRESS
            }
            exampleWave = wave
        }
    }

    /** Blur and strength of the card's background, each with its slider, and their example. */
    private fun bindBackdrop() {
        backdropPreview = null
        val blur = findViewById<Slider>(R.id.backdropBlurSlider)
        val strength = findViewById<Slider>(R.id.backdropStrengthSlider)
        blur.value = onStep(blur, settings.backdropBlur.value)
        strength.value = onStep(strength, settings.backdropStrength.value)
        val example = findViewById<View>(R.id.backdropPreview)
        val showExample = {
            val preview = backdropPreview ?: BackdropPreview(example, lifecycleScope).also { backdropPreview = it }
            preview.show(settings.backdropBlur.value, settings.backdropStrength.value)
        }
        val section = PreviewSection(
            root = findViewById(R.id.settingsContent),
            header = findViewById(R.id.backdropPreviewHeader),
            chevron = findViewById(R.id.backdropPreviewChevron),
            content = example,
            memory = previews,
            key = KEY_BACKDROP_PREVIEW,
            onOpen = showExample,
        )
        blur.addOnChangeListener { _, value, fromUser ->
            if (fromUser) settings.setBackdropBlur(value.roundToInt())
            if (section.isOpen) showExample()
        }
        strength.addOnChangeListener { _, value, fromUser ->
            if (fromUser) settings.setBackdropStrength(value.roundToInt())
            if (section.isOpen) showExample()
        }
    }

    /** [level] rounded to a step of [slider] and kept in its range: a slider refuses any other value. */
    private fun onStep(slider: Slider, level: Int): Float {
        val step = slider.stepSize
        return ((level / step).roundToInt() * step).coerceIn(slider.valueFrom, slider.valueTo)
    }

    /**
     * How a hidden video looks: the music paused or a black screen, which unfolds whether it
     * covers portrait too; and whether the shortcut goes back to the video.
     */
    private fun bindHiding() {
        val card = findViewById<ViewGroup>(R.id.hideCard)
        val blackoutPortrait = findViewById<View>(R.id.blackoutPortraitGroup)
        blackoutPortrait.isVisible = settings.hideStyle.value == HideStyle.BLACKOUT
        val group = findViewById<RadioGroup>(R.id.hideStyleGroup)
        group.check(if (settings.hideStyle.value == HideStyle.BLACKOUT) R.id.hideStyleBlack else R.id.hideStyleMusic)
        group.setOnCheckedChangeListener { _, id ->
            val style = if (id == R.id.hideStyleBlack) HideStyle.BLACKOUT else HideStyle.MUSIC
            settings.setHideStyle(style)
            TransitionManager.beginDelayedTransition(card)
            blackoutPortrait.isVisible = style == HideStyle.BLACKOUT
        }
        bindSwitch(R.id.blackoutPortraitSwitch, settings.blackoutInPortrait.value, settings::setBlackoutInPortrait)
        bindSwitch(R.id.returnToVideoSwitch, settings.returnToVideo.value, settings::setReturnToVideo)
    }

    /** Checks [checked] in the toggle group [id] and reports every new choice to [choose]. */
    private fun bindToggle(@IdRes id: Int, @IdRes checked: Int, choose: (Int) -> Unit) {
        findViewById<MaterialButtonToggleGroup>(id).apply {
            check(checked)
            addOnButtonCheckedListener { _, button, isChecked -> if (isChecked) choose(button) }
        }
    }

    /** Shows [value] in the switch [id] and saves every change with [save]. */
    private fun bindSwitch(@IdRes id: Int, value: Boolean, save: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked -> save(checked) }
        }
    }

    /** Asks before deleting the whole history, which cannot be undone. */
    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear_title)
            .setMessage(R.string.settings_clear_message)
            .setNegativeButton(R.string.settings_clear_cancel, null)
            .setPositiveButton(R.string.settings_clear_confirm) { _, _ ->
                lifecycleScope.launch {
                    HistoryRepository.get(this@SettingsActivity).clear()
                    Toast.makeText(this@SettingsActivity, R.string.settings_cleared, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** The app's version, such as "2.3". */
    private fun versionName(): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return info.versionName.orEmpty()
    }

    private companion object {

        /** How far the color example's seek bar is, out of 100. */
        const val EXAMPLE_PROGRESS = 60

        /** Keys of the examples' open state. */
        const val KEY_ACCENT_PREVIEW = "accent_preview_open"
        const val KEY_BACKDROP_PREVIEW = "backdrop_preview_open"
    }
}
