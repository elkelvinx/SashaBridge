package io.github.elkelvinx.sashabridge.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.PlayerQueue

/**
 * Bottom sheet with the play queue of the active player.
 *
 * The track playing now is highlighted and the list opens scrolled to it; touching a track
 * jumps to it. Main thread only.
 */
object QueueSheet {

    /**
     * Shows [queue].
     *
     * @param context Activity context.
     * @param queue Queue to show.
     * @param appName Name of the player, such as "Spotify".
     * @param onSelect Called with the [io.github.elkelvinx.sashabridge.media.QueueEntry.queueId]
     *   of the touched track.
     */
    // The sheet's content has no parent until setContentView, which sets its layout params.
    @SuppressLint("InflateParams")
    fun show(context: Context, queue: PlayerQueue, appName: String, onSelect: (Long) -> Unit) {
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val sheet = inflater.inflate(R.layout.sheet_queue, null)
        sheet.findViewById<TextView>(R.id.queueTitle).text =
            queue.title ?: context.getString(R.string.queue_title_default)
        sheet.findViewById<TextView>(R.id.queueSource).text =
            context.getString(R.string.now_playing_source, appName)

        val list = sheet.findViewById<LinearLayout>(R.id.queueList)
        val highlight = ContextCompat.getColor(context, R.color.seek_thumb)
        var activeRow: View? = null
        queue.entries.forEachIndexed { index, entry ->
            val row = inflater.inflate(R.layout.item_queue, list, false)
            val active = entry.queueId == queue.activeQueueId
            row.findViewById<TextView>(R.id.queueNumber).apply {
                text = context.getString(R.string.queue_position, index + 1)
                isVisible = !active
            }
            row.findViewById<ImageView>(R.id.queuePlaying).isVisible = active
            row.findViewById<TextView>(R.id.queueItemTitle).apply {
                text = entry.title ?: context.getString(R.string.nothing_playing)
                if (active) {
                    setTextColor(highlight)
                    setTypeface(typeface, Typeface.BOLD)
                }
            }
            row.findViewById<TextView>(R.id.queueItemSubtitle).apply {
                text = entry.subtitle
                isVisible = !entry.subtitle.isNullOrEmpty()
            }
            row.setOnClickListener {
                onSelect(entry.queueId)
                dialog.dismiss()
            }
            if (active) activeRow = row
            list.addView(row)
        }

        dialog.setContentView(sheet)
        dialog.behavior.skipCollapsed = true
        // Open in full: in landscape the peek height hides part of it, and dragging it up from
        // the bottom edge is the system's home gesture.
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
        activeRow?.let { row ->
            sheet.findViewById<NestedScrollView>(R.id.queueScroll).doOnLayout { scroll ->
                (scroll as NestedScrollView).scrollTo(0, row.top)
            }
        }
    }
}
