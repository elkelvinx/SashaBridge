package io.github.elkelvinx.sashabridge

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.elkelvinx.sashabridge.media.PlayerCommand
import io.github.elkelvinx.sashabridge.pen.AnnotateActivity
import io.github.elkelvinx.sashabridge.protocol.ProtocolCodec
import io.github.elkelvinx.sashabridge.ui.BridgePhase
import io.github.elkelvinx.sashabridge.ui.PlayerCard
import io.github.elkelvinx.sashabridge.ui.QueueSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Main screen: the state of the bridge, the track of the active player with its controls
 * ([PlayerCard]), and the settings the bridge needs.
 *
 * Everything shown comes from [BridgeRepository] and from the system, observed while the
 * screen is visible, so it matches what the service does, also after rotating or reopening
 * the app (A7). The on/off button only writes [BridgeRepository.enabled].
 *
 * Sasha, the dachshund the app is named after, shows the state too: she chases a tennis ball
 * while a PC is connected, stopping now and then to drink from her bucket, wags her tail next
 * to an hourglass while she waits for one, and sleeps while the bridge is off.
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

    /** Pose that the state of the bridge asks for. */
    private var phasePose: SashaPose? = null

    /** Whether Sasha is taking one of her drinks, which she only does while playing. */
    private var drinking = false

    /**
     * Asks for the notification permission, which lets a capture from the PC wait behind a
     * notification while "Anotar" is closed; the screen opens whatever the answer.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { openAnnotate() }

    /** Display names of player packages, resolved once each. */
    private val appLabels = HashMap<String, String>()

    /** Launcher icons of player packages, resolved once each; `null` when not visible. */
    private val appIcons = HashMap<String, Drawable?>()

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var sasha: ImageView
    private lateinit var hourglass: ImageView
    private lateinit var grantAccessButton: Button
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
        batteryCard = findViewById(R.id.batteryCard)
        toggleButton = findViewById(R.id.toggleButton)
        findViewById<TextView>(R.id.footer).text =
            getString(R.string.footer, BridgeConfig.PORT, ProtocolCodec.PROTOCOL_VERSION)

        toggleButton.setOnClickListener { repository.setEnabled(!repository.enabled.value) }
        findViewById<Button>(R.id.annotateButton).setOnClickListener {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openAnnotate()
            }
        }
        grantAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        val player = PlayerCard(
            root = findViewById(R.id.main),
            onCommand = repository::sendCommand,
            onOpenQueue = ::openQueue,
            appLabel = ::appLabel,
            appIcon = ::appIcon,
        )

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
                launch { repository.media.collect(player::show) }
                launch { repository.queue.collect { player.setQueueAvailable(it != null) } }
                launch {
                    while (true) {
                        player.tick()
                        delay(PROGRESS_TICK_MS)
                    }
                }
                launch { drinkNowAndThen() }
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
        phasePose = pose
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
        showPose(currentPose(pose))
    }

    /** [pose], unless Sasha is drinking in the middle of her game. */
    private fun currentPose(pose: SashaPose): SashaPose =
        if (drinking && pose == SashaPose.RUNNING) SashaPose.DRINKING else pose

    /**
     * Every minute or two, at random, while she is playing, Sasha stops for a drink and goes
     * back to the ball. Runs while the screen is visible; stopping it ends the drink.
     */
    private suspend fun drinkNowAndThen() {
        try {
            while (true) {
                delay(Random.nextLong(DRINK_INTERVAL_MIN_MS, DRINK_INTERVAL_MAX_MS))
                val pose = phasePose ?: continue
                if (pose != SashaPose.RUNNING) continue
                drinking = true
                showPose(currentPose(pose))
                delay(DRINK_MS)
                drinking = false
                phasePose?.let { showPose(currentPose(it)) }
            }
        } finally {
            drinking = false
        }
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

    /**
     * Returns the launcher icon of a player, such as Spotify's, or `null` if Android does not
     * let this app see it, in which case the screen shows no icon.
     */
    private fun appIcon(packageName: String): Drawable? = appIcons.getOrPut(packageName) {
        try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Opens the S Pen annotation screen. */
    private fun openAnnotate() {
        startActivity(Intent(this, AnnotateActivity::class.java))
    }

    /** Opens the play queue of the active player; touching a track jumps to it. */
    private fun openQueue() {
        val queue = repository.queue.value ?: return
        QueueSheet.show(this, queue, appLabel(queue.source)) { queueId ->
            repository.sendCommand(PlayerCommand.SkipToQueueItem(queueId))
        }
    }

    /** Resolves a color resource for the current theme. */
    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    private companion object {

        /** Interval between seek bar updates while the screen is visible. */
        const val PROGRESS_TICK_MS = 250L

        /** Sasha drinks once every 60 to 120 seconds while she plays, for about 4 seconds. */
        const val DRINK_INTERVAL_MIN_MS = 60_000L
        const val DRINK_INTERVAL_MAX_MS = 120_000L
        const val DRINK_MS = 4_000L
    }

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

        /** Drinking from her bucket, a short break from the ball. */
        DRINKING(R.drawable.sasha_drinking),

        /** Asleep. */
        SLEEPING(R.drawable.sasha_sleeping),
    }
}
