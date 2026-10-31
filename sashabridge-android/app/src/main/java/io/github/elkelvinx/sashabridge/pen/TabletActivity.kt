package io.github.elkelvinx.sashabridge.pen

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.protocol.PenSampleCodec
import io.github.elkelvinx.sashabridge.protocol.PhoneMessage
import io.github.elkelvinx.sashabridge.settings.Appearance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Tableta": the phone becomes a drawing tablet for the PC.
 *
 * The S Pen moves the PC's pen: the tip draws with pressure, hovering moves the cursor, the
 * side button and the eraser end work as on a Windows pen. The drawing area has the shape of
 * the PC's monitor; the monitor button cycles through the PC's monitors. While the screen is
 * open the PC knows it ([TabletRepository.active]), and the phone measures the latency once a
 * second.
 *
 * Main thread.
 */
class TabletActivity : AppCompatActivity() {

    private val tablet = TabletRepository.shared
    private val annotations = AnnotationRepository.shared

    private lateinit var surface: TabletSurfaceView
    private lateinit var monitorButton: View
    private lateinit var monitorLabel: TextView
    private lateinit var latencyLabel: TextView
    private lateinit var pcWarning: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Appearance.applyAccent(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tablet)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // A swipe from the top or bottom edge shows the bars for a moment over the surface.
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val root = findViewById<View>(R.id.tabletRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
            insets
        }

        surface = findViewById(R.id.tabletSurface)
        monitorButton = findViewById(R.id.monitorButton)
        monitorLabel = findViewById(R.id.monitorLabel)
        latencyLabel = findViewById(R.id.latencyLabel)
        pcWarning = findViewById(R.id.tabletPcWarning)

        surface.onSamples = { samples -> tablet.sendSamples(PenSampleCodec.encode(samples)) }
        findViewById<View>(R.id.tabletCloseButton).setOnClickListener { finish() }
        monitorButton.setOnClickListener { nextMonitor() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { tablet.target.collect { showTarget() } }
                launch { annotations.penConnected.collect { pcWarning.isVisible = !it } }
                launch {
                    tablet.latencyMs.collect { latency ->
                        latencyLabel.text = latency?.let { getString(R.string.tablet_latency, it) }
                            ?: getString(R.string.tablet_latency_unknown)
                    }
                }
                launch {
                    while (true) {
                        tablet.send(PhoneMessage.TabletPing(TabletRepository.wireTime(SystemClock.uptimeMillis())))
                        delay(PING_INTERVAL_MS)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        tablet.setActive(true)
    }

    override fun onStop() {
        surface.lift()
        tablet.setActive(false)
        super.onStop()
    }

    /** Shows the monitor the PC draws on and gives the area its shape. */
    private fun showTarget() {
        val target = tablet.target.value
        monitorButton.isEnabled = target != null && target.count > 1
        monitorButton.alpha = if (monitorButton.isEnabled) 1f else DISABLED_ALPHA
        if (target == null) {
            monitorLabel.text = getString(R.string.tablet_monitor_unknown)
            return
        }
        surface.targetAspect = target.width.toFloat() / target.height
        monitorLabel.text = getString(R.string.tablet_monitor_short, target.monitor + 1, target.count)
        monitorButton.contentDescription =
            getString(R.string.tablet_monitor_description, target.monitor + 1, target.count, target.name)
    }

    /** Asks the PC to draw on its next monitor; the area changes when the PC confirms. */
    private fun nextMonitor() {
        val target = tablet.target.value ?: return
        tablet.send(PhoneMessage.TabletSelect((target.monitor + 1) % target.count))
    }

    private companion object {
        /** Interval between latency measurements. */
        const val PING_INTERVAL_MS = 1_000L

        const val DISABLED_ALPHA = 0.4f
    }
}
