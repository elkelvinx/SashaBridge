package io.github.elkelvinx.sashabridge.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.ThumbnailStore
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Rows of the history screen: day headings, which fold their day away, and tracks, each
 * track with its cover.
 *
 * Main thread only.
 *
 * @param dayLabel Text of a day heading, such as "Hoy".
 * @param subtitle Second line of a track: its artist and its player.
 * @param time Time a track played, such as "14:32".
 * @param thumbnails Where the covers come from.
 * @param scope Scope of the screen; the covers load in it.
 * @param onOpen Called with the touched track.
 * @param onToggleDay Called with the day whose heading was touched, to fold or unfold it.
 */
internal class HistoryAdapter(
    private val dayLabel: (LocalDate) -> String,
    private val subtitle: (HistoryEntry) -> String,
    private val time: (HistoryEntry) -> String,
    private val thumbnails: ThumbnailStore,
    private val scope: CoroutineScope,
    private val onOpen: (HistoryEntry) -> Unit,
    private val onToggleDay: (LocalDate) -> Unit,
) : ListAdapter<HistoryRow, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HistoryRow.Day -> DAY
        is HistoryRow.Play -> PLAY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == DAY) {
            DayHolder(inflater.inflate(R.layout.item_history_day, parent, false))
        } else {
            PlayHolder(inflater.inflate(R.layout.item_history, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is HistoryRow.Day -> (holder as DayHolder).bind(row, animate = false)
            is HistoryRow.Play -> (holder as PlayHolder).bind(row.entry, row.count)
        }
    }

    /** A day that only folded or unfolded turns its arrow instead of being drawn again. */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = getItem(position)
        if (FOLD in payloads && holder is DayHolder && row is HistoryRow.Day) {
            holder.bind(row, animate = true)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    /** A day heading: its name, how many plays, and the arrow, pointing down while folded. */
    private inner class DayHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.historyDay)
        private val plays: TextView = view.findViewById(R.id.historyDayPlays)
        private val chevron: ImageView = view.findViewById(R.id.historyDayChevron)

        fun bind(row: HistoryRow.Day, animate: Boolean) {
            val context = itemView.context
            label.text = dayLabel(row.date)
            plays.text = context.resources.getQuantityString(R.plurals.history_day_plays, row.plays, row.plays)
            chevron.isVisible = row.foldable
            val rotation = if (row.folded) 0f else OPEN_ROTATION
            if (animate) chevron.animate().rotation(rotation).setDuration(TURN_MS).start() else chevron.rotation = rotation
            itemView.isClickable = row.foldable
            itemView.setOnClickListener { if (row.foldable) onToggleDay(row.date) }
            val state = if (row.folded) R.string.history_day_folded else R.string.history_day_open
            ViewCompat.setStateDescription(itemView, if (row.foldable) context.getString(state) else null)
        }
    }

    /** A play, or several in a row. */
    private inner class PlayHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.historyCover)
        private val title: TextView = view.findViewById(R.id.historyTitle)
        private val subtitleView: TextView = view.findViewById(R.id.historySubtitle)
        private val timeView: TextView = view.findViewById(R.id.historyTime)
        private val countView: TextView = view.findViewById(R.id.historyCount)

        /** Cover this row shows, so a cover arriving late does not land on a reused row. */
        private var coverKey: String? = null

        fun bind(entry: HistoryEntry, count: Int) {
            title.text = entry.title ?: itemView.context.getString(R.string.nothing_playing)
            subtitleView.text = subtitle(entry)
            timeView.text = time(entry)
            countView.isVisible = count > 1
            countView.text = itemView.context.getString(R.string.history_count, count)
            itemView.setOnClickListener { onOpen(entry) }
            val key = entry.coverRef
            coverKey = key
            val cached = key?.let(thumbnails::cached)
            if (cached != null) cover.setImageBitmap(cached) else cover.setImageResource(R.drawable.cover_placeholder)
            if (key == null || cached != null) return
            scope.launch {
                val bitmap = thumbnails.load(key) ?: return@launch
                if (coverKey == key) cover.setImageBitmap(bitmap)
            }
        }
    }

    /** Rows are the same day or the same play; their content compares by value. */
    private object Diff : DiffUtil.ItemCallback<HistoryRow>() {
        override fun areItemsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean = when {
            oldItem is HistoryRow.Day && newItem is HistoryRow.Day -> oldItem.date == newItem.date
            oldItem is HistoryRow.Play && newItem is HistoryRow.Play -> oldItem.entry.id == newItem.entry.id
            else -> false
        }

        override fun areContentsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean = oldItem == newItem

        /** A day that only folded or unfolded: [FOLD], so its arrow turns in place. */
        override fun getChangePayload(oldItem: HistoryRow, newItem: HistoryRow): Any? {
            val onlyFolding = oldItem is HistoryRow.Day && newItem is HistoryRow.Day && oldItem.copy(folded = newItem.folded) == newItem
            return if (onlyFolding) FOLD else null
        }
    }

    private companion object {
        const val DAY = 0
        const val PLAY = 1

        /** Change payload of a day that folded or unfolded. */
        const val FOLD = "fold"

        /** The arrow points up while the day is open. */
        const val OPEN_ROTATION = 180f
        const val TURN_MS = 200L
    }
}
