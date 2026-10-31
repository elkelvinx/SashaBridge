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
import com.google.android.material.color.MaterialColors
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.PlayerQueue
import io.github.elkelvinx.sashabridge.media.ThumbnailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bottom sheet with the play queue of the active player.
 *
 * Each track shows its cover, as in Spotify, or its number when the player gives no cover;
 * the track playing now has a play sign over it, is highlighted, and the list opens scrolled
 * to it. Touching a track jumps to it. Main thread only.
 */
object QueueSheet {

    /** Duration of a cover fading in when it arrives after the row is shown. */
    private const val COVER_FADE_MS = 150L

    /**
     * Shows [queue].
     *
     * @param context Activity context.
     * @param queue Queue to show.
     * @param appName Name of the player, such as "Spotify".
     * @param thumbnails Where the covers come from.
     * @param scope Scope of the screen, on the main thread; the covers stop loading when the
     *   sheet closes or the screen ends.
     * @param onSelect Called with the [io.github.elkelvinx.sashabridge.media.QueueEntry.queueId]
     *   of the touched track.
     */
    // The sheet's content has no parent until setContentView, which sets its layout params.
    @SuppressLint("InflateParams")
    fun show(
        context: Context,
        queue: PlayerQueue,
        appName: String,
        thumbnails: ThumbnailStore,
        scope: CoroutineScope,
        onSelect: (Long) -> Unit,
    ) {
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val sheet = inflater.inflate(R.layout.sheet_queue, null)
        sheet.findViewById<TextView>(R.id.queueTitle).text =
            queue.title ?: context.getString(R.string.queue_title_default)
        sheet.findViewById<TextView>(R.id.queueSource).text =
            context.getString(R.string.now_playing_source, appName)

        val coverJob = SupervisorJob(scope.coroutineContext[Job])
        val covers = CoroutineScope(scope.coroutineContext + coverJob)
        val list = sheet.findViewById<LinearLayout>(R.id.queueList)
        val highlight = MaterialColors.getColor(context, R.attr.sashaAccent, ContextCompat.getColor(context, R.color.seek_thumb))
        var activeRow: View? = null
        queue.entries.forEachIndexed { index, entry ->
            val row = inflater.inflate(R.layout.item_queue, list, false)
            val active = entry.queueId == queue.activeQueueId
            val artUri = entry.artUri
            row.findViewById<TextView>(R.id.queueNumber).apply {
                text = context.getString(R.string.queue_position, index + 1)
                isVisible = artUri == null && !active
            }
            row.findViewById<View>(R.id.queuePlayingScrim).isVisible = active && artUri != null
            row.findViewById<ImageView>(R.id.queuePlaying).isVisible = active
            row.findViewById<ImageView>(R.id.queueArt).apply {
                isVisible = artUri != null
                if (artUri != null) showCover(this, artUri, thumbnails, covers)
            }
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
        // Covers still downloading keep going in the store; only the rows stop waiting.
        dialog.setOnDismissListener { coverJob.cancel() }
        dialog.show()
        activeRow?.let { row ->
            sheet.findViewById<NestedScrollView>(R.id.queueScroll).doOnLayout { scroll ->
                (scroll as NestedScrollView).scrollTo(0, row.top)
            }
        }
    }

    /** Shows the cover of [artUri] in [view]: at once if it is in memory, else when it arrives. */
    private fun showCover(view: ImageView, artUri: String, thumbnails: ThumbnailStore, scope: CoroutineScope) {
        val cached = thumbnails.cached(artUri)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        scope.launch {
            val cover = thumbnails.load(artUri) ?: return@launch
            view.setImageBitmap(cover)
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(COVER_FADE_MS).start()
        }
    }
}
