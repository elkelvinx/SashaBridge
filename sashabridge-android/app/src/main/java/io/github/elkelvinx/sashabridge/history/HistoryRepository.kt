package io.github.elkelvinx.sashabridge.history

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.media.AlbumArt
import io.github.elkelvinx.sashabridge.media.AlbumArtEncoder
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.media.ThumbnailStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The history of songs listened to and its statistics, over the Room database
 * [HistoryDatabase].
 *
 * - The listener service adds each play ([record]) as [HistoryRecorder] decides, and the time
 *   every app plays ([addAppTime]), as [UsageMeter] measures it.
 * - The history screen lists the plays of the last [KEEP_DAYS] days ([entries]) and reads
 *   them again when [changes] moves; the statistics come from [stats], read again when
 *   [statsChanges] moves.
 * - [prune] deletes the older plays. The songs, their monthly sums and the time per app stay,
 *   so the statistics outlive the plays.
 * - Hiding a video shows the newest song ([latest]) with a larger cover ([latestCover]).
 *
 * Covers: a song whose player gives a web address for its cover ([MediaSnapshot.artUrl])
 * keeps only that address, and its image goes to the thumbnail cache, which Android may empty
 * and which fetches it again when needed. Only songs without an address keep their image on
 * the phone ([ThumbnailStore.keep]).
 *
 * One process-wide instance, [get]. Every member may be called from any coroutine; Room runs
 * the queries off the main thread.
 */
class HistoryRepository private constructor(context: Context) {

    private val database = HistoryDatabase.open(context)
    private val dao = database.dao()
    private val thumbnails = ThumbnailStore.get(context)
    private val filesDir = context.applicationContext.filesDir

    /** Folder of the newest song's cover: one file, named after its song ([saveLatestCover]). */
    private val latestCoverDir = File(filesDir, LATEST_COVER_DIR)

    /** Time zone of the days and months of the statistics. */
    private val zone: ZoneId
        get() = ZoneId.systemDefault()

    /** Moves whenever a play or a song changes, so the list knows to read the plays again. */
    val changes: Flow<Unit> = database.invalidationTracker.createFlow(SONGS, PLAYS).map { }

    /**
     * Moves whenever anything the statistics count changes, the time per app included: while
     * music plays, every few seconds.
     */
    val statsChanges: Flow<Unit> = database.invalidationTracker.createFlow(SONGS, PLAYS, SONG_MONTHS, APP_DAYS).map { }

    /**
     * Adds [snapshot], a song just listened to: its play, its texts and cover, and one more
     * play in its month. A song without a title is no song (a browser, for example) and is
     * left out.
     *
     * @param nowMs When it was listened to, in milliseconds since the epoch.
     */
    suspend fun record(snapshot: MediaSnapshot, nowMs: Long = System.currentTimeMillis()) {
        val key = SongKeys.of(snapshot.source, snapshot.mediaId, snapshot.title, snapshot.artist) ?: return
        val art = snapshot.art
        val artUrl = snapshot.artUrl
        val coverKey = when {
            art == null -> null
            artUrl != null -> {
                // The address is enough; the image only goes to the cache, ready to show.
                thumbnails.remember(artUrl, art.jpeg)
                null
            }
            else -> (COVER_KEY_PREFIX + art.artId).also { thumbnails.keep(it, art.jpeg) }
        }
        val songId = database.useWriterConnection { connection ->
            connection.immediateTransaction { addPlay(snapshot, key, artUrl, coverKey, nowMs) }
        }
        art?.let { saveLatestCover(it, songId) }
    }

    /** Writes a play of [snapshot] and returns its song's id. Inside a transaction. */
    private suspend fun addPlay(snapshot: MediaSnapshot, key: String, artUrl: String?, coverKey: String?, nowMs: Long): Long {
        val title = snapshot.title.orEmpty()
        val kind = AppKinds.songKind(snapshot.source, snapshot.album)
        val existing = dao.song(snapshot.source, key)
        val songId = if (existing == null) {
            dao.insert(
                SongEntity(
                    source = snapshot.source,
                    songKey = key,
                    title = title,
                    artist = snapshot.artist,
                    album = snapshot.album,
                    kind = kind,
                    durationMs = snapshot.durationMs,
                    artUrl = artUrl,
                    coverKey = coverKey,
                    mediaId = snapshot.mediaId,
                    firstPlayedAt = nowMs,
                    lastPlayedAt = nowMs,
                ),
            )
        } else {
            val songArtUrl = artUrl ?: existing.artUrl
            dao.update(
                existing.copy(
                    title = title,
                    artist = snapshot.artist,
                    album = snapshot.album,
                    kind = kind,
                    durationMs = snapshot.durationMs ?: existing.durationMs,
                    artUrl = songArtUrl,
                    coverKey = keptCover(songArtUrl, coverKey, existing.coverKey),
                    mediaId = snapshot.mediaId ?: existing.mediaId,
                    lastPlayedAt = nowMs,
                ),
            )
            existing.id
        }
        dao.insert(PlayEntity(songId = songId, playedAt = nowMs))
        val month = HistoryDates.month(nowMs, zone)
        if (dao.addToMonth(songId, month) == 0) dao.insert(SongMonthEntity(songId, month, plays = 1))
        return songId
    }

    /**
     * Adds [ms] of playing to [source] on the day of [nowMs]; videos and other apps too.
     *
     * @param kind What the app plays, kept with its time.
     */
    suspend fun addAppTime(source: String, kind: AppKind, ms: Long, nowMs: Long = System.currentTimeMillis()) {
        if (ms <= 0) return
        val day = HistoryDates.day(nowMs, zone)
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                if (dao.addToDay(day, source, ms) == 0) dao.insert(AppDayEntity(day, source, kind, ms))
            }
        }
    }

    /**
     * The plays of the last [KEEP_DAYS] days, newest first.
     *
     * @param search Words to find in the title or the artist, or `null` for every play.
     */
    suspend fun entries(search: String? = null): List<HistoryEntry> {
        val since = cutoff(System.currentTimeMillis())
        val text = search?.trim()?.takeIf { it.isNotEmpty() }
        val rows = if (text == null) dao.playsSince(since) else dao.playsSince(since, likePattern(text))
        return rows.map(PlayRow::toEntry)
    }

    /** The newest play, or `null` if the history is empty. */
    suspend fun latest(): HistoryEntry? = dao.latestPlay()?.toEntry()

    /**
     * The cover of [entry], the newest play, as JPEG: as sharp as the player gave it, if it
     * is still the one [record] kept for that song, or else the history's small one.
     *
     * @return The cover, or `null` if the song has none.
     */
    suspend fun latestCover(entry: HistoryEntry): ByteArray? = withContext(Dispatchers.IO) {
        val full = try {
            latestCoverOf(entry.songId).takeIf { it.exists() }?.readBytes()
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Cannot read the cover of the newest song", e)
            null
        }
        full ?: entry.coverRef?.let { thumbnails.load(it) }?.let(::jpegOf)
    }

    /**
     * The statistics of [period] that ends on the day of [nowMs].
     */
    suspend fun stats(period: StatsPeriod, nowMs: Long = System.currentTimeMillis()): HistoryStats {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val (totals, songs, artists) = if (period.days != null) {
            val since = startOfDay(today.minusDays(period.days - 1L))
            Triple(dao.totalsSince(since), dao.topSongsSince(since, TOP_SONGS), dao.topArtistsSince(since, TOP_ARTISTS))
        } else {
            val fromMonth = StatsDates.firstMonth(period, today)
            Triple(dao.totalsFromMonth(fromMonth), dao.topSongsFromMonth(fromMonth, TOP_SONGS), dao.topArtistsFromMonth(fromMonth, TOP_ARTISTS))
        }
        val apps = dao.appTimesFromDay(StatsDates.firstDay(period, today)).map { AppUsage(it.source, it.kind, it.playedMs) }
        val chartFrom = HistoryDates.day(today.minusDays(StatsDates.CHART_DAYS - 1L))
        val days = StatsDates.chartDays(dao.musicTimePerDay(chartFrom).associate { it.day to it.playedMs }, today)
        return HistoryStats(
            period = period,
            listenedMs = apps.filter { it.kind == AppKind.MUSIC }.sumOf { it.playedMs },
            plays = totals.plays,
            songs = totals.songs,
            artists = totals.artists,
            apps = apps,
            topSongs = songs.map { SongStat(it.song.toEntry(), it.plays) },
            topArtists = artists.map { ArtistStat(it.artist, it.plays) },
            days = days,
        )
    }

    /**
     * Deletes the plays older than [KEEP_DAYS] days and the covers kept for songs with no plays
     * left. The songs and the statistics stay. Then gives the freed space back to the phone if
     * it is worth it.
     *
     * All or nothing: the plays and the songs' references to their covers change in one
     * transaction, and only then are the files no song names any more deleted. Cut short, it
     * leaves at most unused files for the next time, never a song naming a deleted file.
     */
    suspend fun prune(nowMs: Long = System.currentTimeMillis()) {
        val inUse = database.useWriterConnection { connection ->
            connection.immediateTransaction {
                dao.deletePlaysBefore(cutoff(nowMs))
                dao.forgetUnusedCovers()
                dao.coverKeysInUse()
            }
        }
        withContext(Dispatchers.IO) { thumbnails.pruneKept(inUse.toSet()) }
        compact()
    }

    /** Deletes the whole history: songs, plays, statistics and kept covers. */
    suspend fun clear() {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                dao.deleteAllPlays()
                dao.deleteAllMonths()
                dao.deleteAllDays()
                dao.deleteAllSongs()
            }
        }
        withContext(Dispatchers.IO) {
            thumbnails.pruneKept(emptySet())
            latestCoverDir.deleteRecursively()
        }
        compact()
    }

    /**
     * Rewrites the file without its free pages when they add up to [COMPACT_BYTES] or more.
     * SQLite keeps the space of deleted rows for later rows instead of shrinking the file.
     */
    private suspend fun compact() {
        database.useWriterConnection { connection ->
            val free = connection.usePrepared("PRAGMA freelist_count") { it.step(); it.getLong(0) }
            val page = connection.usePrepared("PRAGMA page_size") { it.step(); it.getLong(0) }
            if (free * page >= COMPACT_BYTES) {
                Log.i(LOG_TAG, "Compacting the history: ${free * page / BYTES_PER_KB} KB free")
                connection.execSQL("VACUUM")
            }
        }
    }

    /**
     * Keeps [art], the cover of the newest song [songId]: its image for the screen, which a
     * hidden video shows full size on the card.
     *
     * All or nothing: the file is named after its song, written aside and renamed in one step,
     * so a cover is never read as another song's. The older ones are deleted afterwards; one
     * left behind by a cut is still its own song's, and goes with the next.
     */
    private suspend fun saveLatestCover(art: AlbumArt, songId: Long) = withContext(Dispatchers.IO) {
        val jpeg = art.screen?.let { AlbumArtEncoder.compress(it, AlbumArtEncoder.SCREEN_MAX_PX, LATEST_COVER_QUALITY) } ?: art.jpeg
        val target = latestCoverOf(songId)
        val partial = File(latestCoverDir, target.name + ".part")
        try {
            latestCoverDir.mkdirs()
            partial.writeBytes(jpeg)
            if (!partial.renameTo(target)) {
                partial.delete()
                return@withContext
            }
            latestCoverDir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            // The two files of earlier versions, which could disagree after a cut.
            LEGACY_LATEST_COVER_FILES.forEach { File(filesDir, it).delete() }
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Cannot keep the cover of the newest song", e)
        }
    }

    /** The file of the newest song's cover, if [songId] is the newest song. */
    private fun latestCoverOf(songId: Long): File = File(latestCoverDir, "$songId.jpg")

    /** Start of the kept period. */
    private fun cutoff(nowMs: Long): Long = nowMs - TimeUnit.DAYS.toMillis(KEEP_DAYS)

    /** First millisecond of [date] in [zone]. */
    private fun startOfDay(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** [bitmap] as JPEG. */
    private fun jpegOf(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, AlbumArtEncoder.JPEG_QUALITY, it) }
            .toByteArray()

    companion object {

        /** Days the plays are kept; the statistics stay for good. */
        const val KEEP_DAYS = 30L

        /** Songs and artists in the statistics' rankings. */
        private const val TOP_SONGS = 10
        private const val TOP_ARTISTS = 5

        /** Free space at which the file is compacted. */
        private const val COMPACT_BYTES = 256L * 1024
        private const val BYTES_PER_KB = 1024

        /** Start of the thumbnail keys of the covers kept on the phone, followed by the art id. */
        private const val COVER_KEY_PREFIX = "art:"

        /** JPEG quality of the newest song's cover: it is shown large, and it is only one file. */
        private const val LATEST_COVER_QUALITY = 90

        /** Folder, inside the app's files, of the newest song's cover. */
        private const val LATEST_COVER_DIR = "latest-cover"

        /** The newest song's cover and its song id, as two files, before version 2.4. */
        private val LEGACY_LATEST_COVER_FILES = listOf("latest-cover.jpg", "latest-cover.song")

        private const val SONGS = "songs"
        private const val PLAYS = "plays"
        private const val SONG_MONTHS = "song_months"
        private const val APP_DAYS = "app_days"

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

        /**
         * The kept cover of a song played again: none once the song has a web address for its
         * cover, which is what the screen shows ([HistoryEntry.coverRef]); [prune] then deletes
         * the file. Songs migrated from version 1 had no address, so their covers go as they
         * play again. Without an address, the newest kept cover.
         *
         * @param artUrl The song's web address of its cover, old or new.
         * @param newKey The cover kept for this play, or `null`.
         * @param oldKey The cover the song had kept, or `null`.
         */
        internal fun keptCover(artUrl: String?, newKey: String?, oldKey: String?): String? =
            if (artUrl != null) null else newKey ?: oldKey

        /** A LIKE pattern that finds [text] anywhere, with `%`, `_` and `\` taken literally. */
        internal fun likePattern(text: String): String =
            "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }
}

/** The play as the history shows it. */
internal fun PlayRow.toEntry(): HistoryEntry = song.toEntry().copy(id = playId, playedAtMs = playedAt)

/** The song as an entry of the history, dated at its newest play. */
internal fun SongEntity.toEntry(): HistoryEntry = HistoryEntry(
    id = 0,
    playedAtMs = lastPlayedAt,
    source = source,
    title = title,
    artist = artist,
    album = album,
    mediaId = mediaId,
    coverKey = coverKey,
    songId = id,
    kind = kind,
    artUrl = artUrl,
)
