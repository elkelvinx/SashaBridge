package io.github.elkelvinx.sashabridge.history

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.ThumbnailStore
import io.github.elkelvinx.sashabridge.settings.Appearance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The history: the tracks listened to in the last 50 days, newest first and grouped by day,
 * with a search by title or artist. Touching a track opens it in its player ([PlayerLinks]).
 * The list follows new tracks while the screen is open.
 *
 * Main thread.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter
    private lateinit var empty: TextView

    /** Words of the search, or `null`. */
    private var search: String? = null

    /** The reading of the list in progress, replaced by every newer one. */
    private var reload: Job? = null

    /** Names of the players, by package. */
    private val appLabels = HashMap<String, String>()

    private val zone: ZoneId = ZoneId.systemDefault()
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Appearance.applyAccent(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.historyRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.historyToolbar).setNavigationOnClickListener { finish() }
        empty = findViewById(R.id.historyEmpty)

        adapter = HistoryAdapter(
            dayLabel = ::dayLabel,
            subtitle = ::subtitle,
            time = { timeFormat.format(Instant.ofEpochMilli(it.playedAtMs).atZone(zone)) },
            thumbnails = ThumbnailStore.get(this),
            scope = lifecycleScope,
            onOpen = ::open,
        )
        findViewById<RecyclerView>(R.id.historyList).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }
        findViewById<TextInputEditText>(R.id.historySearch).doAfterTextChanged { text ->
            search = text?.toString()
            reload(SEARCH_DELAY_MS)
        }

        val history = HistoryRepository.get(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                history.changes.collect { reload(0L) }
            }
        }
    }

    /** Reads the list again after [delayMs], replacing a reading still waiting. */
    private fun reload(delayMs: Long) {
        reload?.cancel()
        reload = lifecycleScope.launch {
            delay(delayMs)
            val entries = HistoryRepository.get(this@HistoryActivity).entries(search)
            adapter.submitList(HistoryList.rows(entries, zone))
            empty.isVisible = entries.isEmpty()
            empty.setText(if (search.isNullOrBlank()) R.string.history_empty else R.string.history_no_results)
        }
    }

    /** "Hoy", "Ayer", or the weekday and date. */
    private fun dayLabel(date: LocalDate): String {
        val today = LocalDate.now(zone)
        return when (date) {
            today -> getString(R.string.history_today)
            today.minusDays(1) -> getString(R.string.history_yesterday)
            else -> dayFormat.format(date).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    /** The artist and the player of [entry], such as "VK Blanka · YouTube Music". */
    private fun subtitle(entry: HistoryEntry): String {
        val app = appLabel(entry.source)
        return entry.artist?.let { getString(R.string.history_subtitle, it, app) } ?: app
    }

    /** Opens [entry] in its player. */
    private fun open(entry: HistoryEntry) {
        if (!PlayerLinks.open(this, entry)) {
            Toast.makeText(this, getString(R.string.history_open_failed, appLabel(entry.source)), Toast.LENGTH_SHORT).show()
        }
    }

    /** The name of a player, such as "Spotify", or its package if Android does not tell. */
    private fun appLabel(packageName: String): String = appLabels.getOrPut(packageName) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private companion object {

        /** Pause after typing before searching, so each letter does not query the database. */
        const val SEARCH_DELAY_MS = 250L
    }
}
