package io.github.elkelvinx.sashabridge

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig
import io.github.elkelvinx.sashabridge.bridge.BridgeRepository
import io.github.elkelvinx.sashabridge.media.AlbumArt
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.protocol.ProtocolCodec
import io.github.elkelvinx.sashabridge.ui.BridgePhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Main screen: the state of the bridge, the track of the active player, and the settings the
 * bridge needs.
 *
 * Everything shown comes from [BridgeRepository] and from the system, observed while the
 * screen is visible, so it matches what the service does, also after rotating or reopening
 * the app (A7). The on/off button only writes [BridgeRepository.enabled].
 *
 * Sasha, the dachshund the app is named after, shows the state too: she chases a tennis ball
 * while a PC is connected, wags her tail next to an hourglass while she waits for one, and
 * sleeps while the bridge is off.
 *
 * Every member runs on the main thread.
 */
class MainActivity : AppCompatActivity() {

    /** Shared state of the bridge. */
    private lateinit var repository: BridgeRepository

    /** Whether notification access is granted; checked again whenever the screen resumes. */
    private val hasAccess = MutableStateFlow(false)

    /** Pose currently shown, so that an animation is not restarted by an unrelated update. */
    private var shownPose: SashaPose? = null

    /** Identifier of the art currently shown, to decode each image only once. */
    private var shownArtId: String? = null

    /** Display names of player packages, resolved once each. */
    private val appLabels = HashMap<String, String>()

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var sasha: ImageView
    private lateinit var hourglass: ImageView
    private lateinit var grantAccessButton: Button
    private lateinit var cover: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var trackSource: TextView
    private lateinit var batteryCard: View
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        repository = BridgeRepository.get(this)

        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        sasha = findViewById(R.id.sasha)
        hourglass = findViewById(R.id.hourglass)
        grantAccessButton = findViewById(R.id.grantAccessButton)
        cover = findViewById(R.id.cover)
        trackTitle = findViewById(R.id.trackTitle)
        trackArtist = findViewById(R.id.trackArtist)
        trackSource = findViewById(R.id.trackSource)
        batteryCard = findViewById(R.id.batteryCard)
        toggleButton = findViewById(R.id.toggleButton)
        findViewById<TextView>(R.id.footer).text =
            getString(R.string.footer, BridgeConfig.PORT, ProtocolCodec.PROTOCOL_VERSION)

        toggleButton.setOnClickListener { repository.setEnabled(!repository.enabled.value) }
        grantAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        hasAccess,
                        repository.listenerConnected,
                        repository.enabled,
                        repository.status,
                        BridgePhase::of,
                    ).collect(::showPhase)
                }
                launch {
                    repository.enabled.collect { enabled ->
                        toggleButton.setText(if (enabled) R.string.button_turn_off else R.string.button_turn_on)
                    }
                }
                launch { repository.media.collect(::showTrack) }
            }
        }
    }

    /**
     * Checks the settings the user may have changed elsewhere, and asks Android to bind the
     * listener again if access is granted but it is not bound, for example after reinstalling.
     */
    override fun onResume() {
        super.onResume()
        val access = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        hasAccess.value = access
        if (access && !repository.listenerConnected.value) {
            NotificationListenerService.requestRebind(ComponentName(this, MusicListenerService::class.java))
        }
        val power = getSystemService(PowerManager::class.java)
        batteryCard.isVisible = !power.isIgnoringBatteryOptimizations(packageName)
    }

    /** Shows [phase] in the status card. */
    private fun showPhase(phase: BridgePhase) {
        val (dotColor, pose) = when (phase) {
            BridgePhase.NoAccess -> R.color.status_error to SashaPose.AWAKE
            BridgePhase.Disabled -> R.color.status_off to SashaPose.SLEEPING
            BridgePhase.WaitingForListener, BridgePhase.Starting, BridgePhase.Listening ->
                R.color.status_waiting to SashaPose.WAITING
            is BridgePhase.Connected -> R.color.status_connected to SashaPose.RUNNING
            is BridgePhase.Failed -> R.color.status_error to SashaPose.AWAKE
        }
        statusTitle.text = when (phase) {
            BridgePhase.NoAccess -> getString(R.string.phase_no_access)
            BridgePhase.Disabled -> getString(R.string.phase_disabled)
            BridgePhase.WaitingForListener -> getString(R.string.phase_waiting_listener)
            BridgePhase.Starting -> getString(R.string.phase_starting)
            BridgePhase.Listening -> getString(R.string.phase_listening)
            is BridgePhase.Connected -> resources.getQuantityString(R.plurals.phase_connected, phase.clients, phase.clients)
            is BridgePhase.Failed -> getString(R.string.phase_failed)
        }
        statusDetail.text = when (phase) {
            BridgePhase.NoAccess -> getString(R.string.phase_no_access_detail)
            BridgePhase.Disabled -> getString(R.string.phase_disabled_detail)
            BridgePhase.WaitingForListener -> getString(R.string.phase_waiting_listener_detail)
            BridgePhase.Starting -> getString(R.string.phase_starting_detail)
            BridgePhase.Listening -> getString(R.string.phase_listening_detail)
            is BridgePhase.Connected -> getString(R.string.phase_connected_detail)
            is BridgePhase.Failed -> phase.reason
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(color(dotColor))
        grantAccessButton.isVisible = phase == BridgePhase.NoAccess
        // While waiting for a PC the button looks idle, since there is nothing to stop yet;
        // it still works.
        val idle = pose == SashaPose.WAITING
        toggleButton.backgroundTintList =
            ColorStateList.valueOf(color(if (idle) R.color.button_idle else R.color.button_active))
        toggleButton.setTextColor(color(if (idle) R.color.on_surface_muted else R.color.on_primary))
        showPose(pose)
    }

    /** Shows Sasha in [pose], starting its animations, and the hourglass while she waits. */
    private fun showPose(pose: SashaPose) {
        if (pose == shownPose) return
        shownPose = pose
        sasha.setImageResource(pose.drawable)
        (sasha.drawable as? AnimationDrawable)?.start()
        val waiting = pose == SashaPose.WAITING
        hourglass.isVisible = waiting
        (hourglass.drawable as? AnimationDrawable)?.let { animation ->
            if (waiting) {
                // Every wait starts with the sand at the top.
                animation.stop()
                animation.selectDrawable(0)
                animation.start()
            } else {
                animation.stop()
            }
        }
    }

    /** Shows the track of the active player, or that nothing is playing. */
    private fun showTrack(snapshot: MediaSnapshot?) {
        showArt(snapshot?.art)
        if (snapshot == null) {
            trackTitle.setText(R.string.nothing_playing)
            trackArtist.isVisible = false
            trackSource.isVisible = false
            return
        }
        trackTitle.text = snapshot.title ?: getString(R.string.nothing_playing)
        val artist = listOfNotNull(snapshot.artist, snapshot.playbackContext).joinToString(" • ")
        trackArtist.text = artist
        trackArtist.isVisible = artist.isNotEmpty()
        trackSource.text = getString(R.string.now_playing_source, appLabel(snapshot.source))
        trackSource.isVisible = true
    }

    /** Shows [art], decoding it only when it changes, or an empty tile without art. */
    private fun showArt(art: AlbumArt?) {
        if (art?.artId == shownArtId) return
        shownArtId = art?.artId
        cover.setImageBitmap(art?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) })
    }

    /**
     * Returns the name the user knows a player by, such as "Spotify", or the package name if
     * Android does not let this app see it.
     */
    private fun appLabel(packageName: String): String = appLabels.getOrPut(packageName) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    /** Resolves a color resource for the current theme. */
    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    /**
     * Poses of Sasha.
     *
     * @property drawable Still image or animation of the pose.
     */
    private enum class SashaPose(@param:DrawableRes val drawable: Int) {
        /** Standing still, looking ahead. */
        AWAKE(R.drawable.sasha_awake),

        /** Wagging her tail next to a turning hourglass. */
        WAITING(R.drawable.sasha_wagging),

        /** Running after a tennis ball. */
        RUNNING(R.drawable.sasha_running),

        /** Asleep. */
        SLEEPING(R.drawable.sasha_sleeping),
    }
}
