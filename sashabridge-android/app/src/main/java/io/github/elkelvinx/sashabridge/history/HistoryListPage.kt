package io.github.elkelvinx.sashabridge.history

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.ThumbnailStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The list page of the history screen (`page_history`): the plays of the last 30 days, newest
 * first, grouped by day, with the same song in a row folded into one row ("×3"), and a search
 * by title or artist. Touching the heading of a day folds its plays away, or unfolds them;
 * folded days stay folded while the screen is open, but a search shows every day open.
 *
 * Main thread.
 *
 * @param onOpen Opens a play in its player.
 */
internal class HistoryListPage(
    view: View,
    private val scope: CoroutineScope,
    private val history: HistoryRepository,
    private val apps: AppNames,
    thumbnails: ThumbnailStore,
    onOpen: (HistoryEntry) -> Unit,
) {

    private val context = view.context
    private val empty: TextView = view.findViewById(R.id.historyEmpty)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.getDefault())

    private val adapter = HistoryAdapter(
        dayLabel = ::dayLabel,
        subtitle = ::subtitle,
        time = { timeFormat.format(Instant.ofEpochMilli(it.playedAtMs).atZone(zone)) },
        thumbnails = thumbnails,
        scope = scope,
        onOpen = onOpen,
        onToggleDay = ::toggle,
    )

    /** Days whose plays are folded away. */
    private val folded = HashSet<LocalDate>()

    /** The plays last read, to fold and unfold days without reading them again. */
    private var entries: List<HistoryEntry> = emptyList()

    /** Words of the search, or `null`. */
    private var search: String? = null

    /** The reading of the list in progress, replaced by every newer one. */
    private var reading: Job? = null

    init {
        view.findViewById<RecyclerView>(R.id.historyList).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@HistoryListPage.adapter
        }
        view.findViewById<TextInputEditText>(R.id.historySearch).doAfterTextChanged { text ->
            search = text?.toString()
            reload(SEARCH_DELAY_MS)
        }
        reload(0L)
    }

    /** Reads the list again after [delayMs], replacing a reading still waiting. */
    fun reload(delayMs: Long = 0L) {
        reading?.cancel()
        reading = scope.launch {
            delay(delayMs)
            entries = history.entries(search)
            show()
            empty.isVisible = entries.isEmpty()
            empty.setText(if (search.isNullOrBlank()) R.string.history_empty else R.string.history_no_results)
        }
    }

    /** Shows [entries] with the folded days folded, unless searching. */
    private fun show() {
        adapter.submitList(HistoryList.rows(entries, zone, folded, foldable = search.isNullOrBlank()))
    }

    /** Folds [date] away, or unfolds it. */
    private fun toggle(date: LocalDate) {
        if (!folded.add(date)) folded.remove(date)
        show()
    }

    /** "Hoy", "Ayer", or the weekday and date. */
    private fun dayLabel(date: LocalDate): String {
        val today = LocalDate.now(zone)
        return when (date) {
            today -> context.getString(R.string.history_today)
            today.minusDays(1) -> context.getString(R.string.history_yesterday)
            else -> dayFormat.format(date).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    /** The artist and the player of [entry], such as "VK Blanka · YouTube Music". */
    private fun subtitle(entry: HistoryEntry): String {
        val app = apps.label(entry.source)
        return entry.artist?.let { context.getString(R.string.history_subtitle, it, app) } ?: app
    }

    private companion object {

        /** Pause after typing before searching, so each letter does not query the database. */
        const val SEARCH_DELAY_MS = 250L
    }
}
