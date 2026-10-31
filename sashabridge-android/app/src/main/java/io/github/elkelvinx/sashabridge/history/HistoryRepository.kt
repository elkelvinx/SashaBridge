package io.github.elkelvinx.sashabridge.history

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.media.AlbumArt
import io.github.elkelvinx.sashabridge.media.AlbumArtEncoder
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.media.ThumbnailStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
 * track names any more, are deleted by [prune]. Hiding a video shows the newest track
 * ([latest]) with a larger cover than the list's ([latestCover]).
 *
 * One process-wide instance, [get]. Every member may be called from any coroutine; the work
 * runs on [Dispatchers.IO].
 */
class HistoryRepository private constructor(context: Context) {

    private val database = HistoryDatabase(context.applicationContext)
    private val thumbnails = ThumbnailStore.get(context)
    private val latestCoverFile = File(context.applicationContext.filesDir, LATEST_COVER_FILE)
    private val mutableChanges = MutableStateFlow(0L)

    /** Counts the writes, so a screen that shows the history knows when to read it again. */
    val changes: StateFlow<Long> = mutableChanges.asStateFlow()

    /**
     * Adds [snapshot], a track just listened to, with its cover. If the newest track of the
     * history is the same song (forward, then back to it), that one moves to [nowMs] instead:
     * a song played again right away is one listening, not several.
     *
     * @param nowMs When it was listened to, in milliseconds since the epoch.
     */
    suspend fun record(snapshot: MediaSnapshot, nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val coverKey = snapshot.art?.let { art -> (COVER_KEY_PREFIX + art.artId).also { thumbnails.keep(it, art.jpeg) } }
        val latest = database.latest()
        if (latest != null && latest.isSameSong(snapshot.source, snapshot.title, snapshot.artist)) {
            database.touch(latest.id, nowMs, coverKey)
        } else {
            database.insert(nowMs, snapshot.source, snapshot.title, snapshot.artist, snapshot.album, snapshot.mediaId, coverKey)
        }
        snapshot.art?.let(::saveLatestCover)
        mutableChanges.update { it + 1 }
    }

    /** The newest track, or `null` if the history is empty. */
    suspend fun latest(): HistoryEntry? = withContext(Dispatchers.IO) { database.latest() }

    /**
     * The cover of [entry], the newest track, as JPEG: as the player gave it, if it is still
     * the one kept by [record], or else the history's small one.
     *
     * @return The cover, or `null` if the track had none.
     */
    suspend fun latestCover(entry: HistoryEntry): ByteArray? = withContext(Dispatchers.IO) {
        val key = entry.coverKey ?: return@withContext null
        val full = try {
            latestCoverFile.takeIf { it.exists() }?.readBytes()
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Cannot read the cover of the newest track", e)
            null
        }
        full?.takeIf { COVER_KEY_PREFIX + AlbumArtEncoder.artIdOf(it) == key }
            ?: thumbnails.load(key)?.let(::jpegOf)
    }

    /** Keeps [art], the cover of the newest track, in one file that each new one replaces. IO thread. */
    private fun saveLatestCover(art: AlbumArt) {
        val partial = File(latestCoverFile.path + ".part")
        try {
            partial.writeBytes(art.jpeg)
            if (!partial.renameTo(latestCoverFile)) partial.delete()
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Cannot keep the cover of the newest track", e)
        }
    }

    /** [bitmap] as JPEG. */
    private fun jpegOf(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, AlbumArtEncoder.JPEG_QUALITY, it) }
            .toByteArray()

    /**
     * The tracks of the last [KEEP_DAYS] days, newest first.
     *
     * @param search Words to find in the title or the artist, or `null` for every track.
     */
    suspend fun entries(search: String? = null): List<HistoryEntry> = withContext(Dispatchers.IO) {
        database.since(cutoff(System.currentTimeMillis()), search)
    }

    /**
     * Deletes the tracks older than [KEEP_DAYS] days, the repeats of a song listened to
     * several times in a row ([HistoryList.repeats]), and the covers no track names.
     */
    suspend fun prune(nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        database.deleteBefore(cutoff(nowMs))
        database.delete(HistoryList.repeats(database.since(cutoff(nowMs), null)))
        thumbnails.pruneKept(database.coverKeys())
    }

    /** Deletes every track, with its covers. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        database.deleteAll()
        thumbnails.pruneKept(emptySet())
        latestCoverFile.delete()
        mutableChanges.update { it + 1 }
    }

    /** Start of the kept period. */
    private fun cutoff(nowMs: Long): Long = nowMs - TimeUnit.DAYS.toMillis(KEEP_DAYS)

    companion object {

        /** Days the history keeps. */
        const val KEEP_DAYS = 50L

        /** Start of the thumbnail keys of the history's covers, followed by the art id. */
        private const val COVER_KEY_PREFIX = "art:"

        /** File, inside the app's files, with the cover of the newest track as the player gave it. */
        private const val LATEST_COVER_FILE = "latest-cover.jpg"

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
