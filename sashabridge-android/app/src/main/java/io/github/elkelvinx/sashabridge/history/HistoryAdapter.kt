package io.github.elkelvinx.sashabridge.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.ThumbnailStore
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Rows of the history screen: day headings and tracks, each track with its cover.
 *
 * Main thread only.
 *
 * @param dayLabel Text of a day heading, such as "Hoy".
 * @param subtitle Second line of a track: its artist and its player.
 * @param time Time a track played, such as "14:32".
 * @param thumbnails Where the covers come from.
 * @param scope Scope of the screen; the covers load in it.
 * @param onOpen Called with the touched track.
 */
internal class HistoryAdapter(
    private val dayLabel: (LocalDate) -> String,
    private val subtitle: (HistoryEntry) -> String,
    private val time: (HistoryEntry) -> String,
    private val thumbnails: ThumbnailStore,
    private val scope: CoroutineScope,
    private val onOpen: (HistoryEntry) -> Unit,
) : ListAdapter<HistoryRow, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HistoryRow.Day -> DAY
        is HistoryRow.Play -> PLAY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == DAY) {
            DayHolder(inflater.inflate(R.layout.item_history_day, parent, false) as TextView)
        } else {
            PlayHolder(inflater.inflate(R.layout.item_history, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is HistoryRow.Day -> (holder as DayHolder).label.text = dayLabel(row.date)
            is HistoryRow.Play -> (holder as PlayHolder).bind(row.entry)
        }
    }

    /** A day heading. */
    private class DayHolder(val label: TextView) : RecyclerView.ViewHolder(label)

    /** A track. */
    private inner class PlayHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.historyCover)
        private val title: TextView = view.findViewById(R.id.historyTitle)
        private val subtitleView: TextView = view.findViewById(R.id.historySubtitle)
        private val timeView: TextView = view.findViewById(R.id.historyTime)

        /** Cover this row shows, so a cover arriving late does not land on a reused row. */
        private var coverKey: String? = null

        fun bind(entry: HistoryEntry) {
            title.text = entry.title ?: itemView.context.getString(R.string.nothing_playing)
            subtitleView.text = subtitle(entry)
            timeView.text = time(entry)
            itemView.setOnClickListener { onOpen(entry) }
            val key = entry.coverKey
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
    }

    private companion object {
        const val DAY = 0
        const val PLAY = 1
    }
}
