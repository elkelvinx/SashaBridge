package io.github.elkelvinx.sashabridge.history

import android.content.Context
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.media.ThumbnailStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The tracks listened to in the last [KEEP_DAYS] days, with their small covers.
 *
 * The listener service adds them ([record]) as [HistoryRecorder] decides; the history screen
 * reads them ([entries]) and reloads when [changes] moves. Older tracks, and the covers no
 * track names any more, are deleted by [prune].
 *
 * One process-wide instance, [get]. Every member may be called from any coroutine; the work
 * runs on [Dispatchers.IO].
 */
class HistoryRepository private constructor(context: Context) {

    private val database = HistoryDatabase(context.applicationContext)
    private val thumbnails = ThumbnailStore.get(context)
    private val mutableChanges = MutableStateFlow(0L)

    /** Counts the writes, so a screen that shows the history knows when to read it again. */
    val changes: StateFlow<Long> = mutableChanges.asStateFlow()

    /**
     * Adds [snapshot], a track just listened to, with its cover.
     *
     * @param nowMs When it was listened to, in milliseconds since the epoch.
     */
    suspend fun record(snapshot: MediaSnapshot, nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val coverKey = snapshot.art?.let { art -> (COVER_KEY_PREFIX + art.artId).also { thumbnails.keep(it, art.jpeg) } }
        database.insert(nowMs, snapshot.source, snapshot.title, snapshot.artist, snapshot.album, snapshot.mediaId, coverKey)
        mutableChanges.update { it + 1 }
    }

    /**
     * The tracks of the last [KEEP_DAYS] days, newest first.
     *
     * @param search Words to find in the title or the artist, or `null` for every track.
     */
    suspend fun entries(search: String? = null): List<HistoryEntry> = withContext(Dispatchers.IO) {
        database.since(cutoff(System.currentTimeMillis()), search)
    }

    /** Deletes the tracks older than [KEEP_DAYS] days and the covers no track names. */
    suspend fun prune(nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        database.deleteBefore(cutoff(nowMs))
        thumbnails.pruneKept(database.coverKeys())
    }

    /** Start of the kept period. */
    private fun cutoff(nowMs: Long): Long = nowMs - TimeUnit.DAYS.toMillis(KEEP_DAYS)

    companion object {

        /** Days the history keeps. */
        const val KEEP_DAYS = 50L

        /** Start of the thumbnail keys of the history's covers, followed by the art id. */
        private const val COVER_KEY_PREFIX = "art:"

        @Volatile
        private var instance: HistoryRepository? = null

        /**
         * Returns the process-wide instance, creating it on first use. Any thread.
         *
         * @param context Any context; only the application context is kept.
         */
        fun get(context: Context): HistoryRepository = instance ?: synchronized(this) {
            instance ?: HistoryRepository(context).also { instance = it }
        }
    }
}
