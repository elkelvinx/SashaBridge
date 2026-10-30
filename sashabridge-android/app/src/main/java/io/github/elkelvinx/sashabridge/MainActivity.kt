package io.github.elkelvinx.sashabridge

import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.media.session.MediaSessionManager
import android.os.BatteryManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig
import io.github.elkelvinx.sashabridge.bridge.BridgeRepository
import io.github.elkelvinx.sashabridge.media.PlayerCommand
import io.github.elkelvinx.sashabridge.pen.AnnotateActivity
import io.github.elkelvinx.sashabridge.pen.TabletActivity
import io.github.elkelvinx.sashabridge.protocol.ProtocolCodec
import io.github.elkelvinx.sashabridge.ui.BridgePhase
import io.github.elkelvinx.sashabridge.ui.PlayerCard
import io.github.elkelvinx.sashabridge.ui.QueueSheet
import io.github.elkelvinx.sashabridge.ui.SashaView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Main screen: the state of the bridge, the track of the active player with its controls
 * ([PlayerCard]), and the settings the bridge needs.
 *
 * Everything shown comes from [BridgeRepository] and from the system, observed while the
 * screen is visible, so it matches what the service does, also after rotating or reopening
 * the app (A7). The on/off button only writes [BridgeRepository.enabled].
 *
 * Sasha, the dachshund the app is named after, shows the state too ([SashaView]).
 *
 * In landscape the screen is meant to stay on as a display next to the PC, so it saves space
 * and power: no status bar (the status card shows the battery and the time instead), the
 * screen stays on only while the phone charges, and every minute the content moves a few
 * pixels so no part of the OLED panel shows the same thing for hours.
 *
 * Every member runs on the main thread.
 */
class MainActivity : AppCompatActivity() {

    /** Shared state of the bridge. */
    private lateinit var repository: BridgeRepository

    /** Whether notification access is granted; checked again whenever the screen resumes. */
    private val hasAccess = MutableStateFlow(false)

    /** Whether the screen is in landscape, the display layout. */
    private val landscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Updates the battery level and keeps the screen on while charging in landscape. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showBattery(intent)
    }

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
    private lateinit var sasha: SashaView
    private var batteryLabel: TextView? = null
    private lateinit var grantAccessButton: Button
    private lateinit var batteryCard: View
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        if (landscape) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                // A swipe from the top shows the status bar for a moment.
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        repository = BridgeRepository.get(this)

        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        sasha = findViewById(R.id.sasha)
        batteryLabel = findViewById(R.id.batteryLabel)
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
        findViewById<Button>(R.id.tabletButton).setOnClickListener {
            startActivity(Intent(this, TabletActivity::class.java))
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
            onOpenPlayer = ::openPlayer,
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
                if (landscape) launch { shiftNowAndThen() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The battery broadcast is sticky: registering returns the current state at once.
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let(::showBattery)
    }

    override fun onStop() {
        unregisterReceiver(batteryReceiver)
        super.onStop()
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
            BridgePhase.NoAccess -> R.color.status_error to SashaView.Pose.AWAKE
            BridgePhase.Disabled -> R.color.status_off to SashaView.Pose.SLEEPING
            BridgePhase.WaitingForListener, BridgePhase.Starting, BridgePhase.Listening ->
                R.color.status_waiting to SashaView.Pose.WAITING
            is BridgePhase.Connected -> R.color.status_connected to SashaView.Pose.PLAYING
            is BridgePhase.Failed -> R.color.status_error to SashaView.Pose.AWAKE
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
        val idle = pose == SashaView.Pose.WAITING
        toggleButton.backgroundTintList =
            ColorStateList.valueOf(color(if (idle) R.color.button_idle else R.color.button_active))
        toggleButton.setTextColor(color(if (idle) R.color.on_surface_muted else R.color.on_primary))
        sasha.pose = pose
    }

    /**
     * Shows the battery level in the status card (landscape only, where the status bar is
     * hidden) and, in landscape, keeps the screen on while the phone charges.
     *
     * @param battery An [Intent.ACTION_BATTERY_CHANGED] broadcast.
     */
    private fun showBattery(battery: Intent) {
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        batteryLabel?.let { label ->
            label.isVisible = level >= 0 && scale > 0
            if (label.isVisible) label.text = getString(R.string.battery_level, level * PERCENT / scale)
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (charging) R.drawable.ic_bolt else 0, 0, 0, 0,
            )
        }
        if (landscape && charging) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Every minute moves the content to the next of a few positions within [SHIFT_DP] of its
     * place, so a display left on for hours does not wear the same pixels of the OLED panel.
     * Runs while the screen is visible.
     */
    private suspend fun shiftNowAndThen() {
        // Only the landscape layout has it.
        val content = findViewById<View?>(R.id.mainContent) ?: return
        val step = SHIFT_DP * resources.displayMetrics.density
        var position = 0
        try {
            while (true) {
                delay(SHIFT_INTERVAL_MS)
                position = (position + 1) % SHIFTS.size
                content.translationX = SHIFTS[position].first * step
                content.translationY = SHIFTS[position].second * step
            }
        } finally {
            content.translationX = 0f
            content.translationY = 0f
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

    /**
     * Opens a player app on the screen its media session names, usually the one of the track
     * playing; failing that, on its launcher screen.
     *
     * @param packageName The player, such as Spotify's package.
     */
    private fun openPlayer(packageName: String) {
        val playerScreen = try {
            getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(this, MusicListenerService::class.java))
                ?.firstOrNull { it.packageName == packageName }
                ?.sessionActivity
        } catch (_: SecurityException) {
            // Notification access was taken away; the launcher screen still works.
            null
        }
        if (playerScreen != null) {
            try {
                playerScreen.send(this, 0, null, null, null, null, playerScreenOptions())
                return
            } catch (_: PendingIntent.CanceledException) {
                // The player dropped that screen; fall back to its launcher screen.
            }
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
    }

    /**
     * Lets the player's own pending intent open its screen while this one is visible, which
     * Android 14 and later require from the app that sends it.
     */
    private fun playerScreenOptions(): Bundle? {
        val mode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                @Suppress("DEPRECATION")
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            else -> return null
        }
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
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
        const val PROGRESS_TICK_MS = 500L

        /** Battery levels are shown in percent. */
        const val PERCENT = 100

        /** The landscape content moves every minute, by up to 3 dp, through these offsets. */
        const val SHIFT_INTERVAL_MS = 60_000L
        const val SHIFT_DP = 3f
        val SHIFTS = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1)
    }
}
