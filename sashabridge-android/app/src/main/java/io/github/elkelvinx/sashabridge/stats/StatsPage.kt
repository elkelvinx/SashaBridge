package io.github.elkelvinx.sashabridge.stats

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.history.AppKind
import io.github.elkelvinx.sashabridge.history.AppNames
import io.github.elkelvinx.sashabridge.history.AppUsage
import io.github.elkelvinx.sashabridge.history.ArtistStat
import io.github.elkelvinx.sashabridge.history.HistoryEntry
import io.github.elkelvinx.sashabridge.history.HistoryRepository
import io.github.elkelvinx.sashabridge.history.HistoryStats
import io.github.elkelvinx.sashabridge.history.SongKind
import io.github.elkelvinx.sashabridge.history.SongStat
import io.github.elkelvinx.sashabridge.history.StatsPeriod
import io.github.elkelvinx.sashabridge.media.ThumbnailStore
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The statistics page of the history screen (`page_stats`): the period, the totals, the
 * music per day, the time per app, and the most played songs and artists.
 *
 * The data comes from [HistoryRepository.stats] and is read again on [load], which the screen
 * calls when the history changes; while music plays that is every few seconds, so each part is
 * only drawn again when its own data changed.
 *
 * Main thread.
 *
 * @param onOpen Opens a song in its player, as the history does.
 */
internal class StatsPage(
    view: View,
    private val scope: CoroutineScope,
    private val history: HistoryRepository,
    private val apps: AppNames,
    private val thumbnails: ThumbnailStore,
    private val onOpen: (HistoryEntry) -> Unit,
) {

    private val context = view.context
    private val inflater = LayoutInflater.from(context)
    private val empty: View = view.findViewById(R.id.statsEmpty)
    private val body: View = view.findViewById(R.id.statsBody)
    private val listened: TextView = view.findViewById(R.id.statListened)
    private val plays: TextView = view.findViewById(R.id.statPlays)
    private val songs: TextView = view.findViewById(R.id.statSongs)
    private val artists: TextView = view.findViewById(R.id.statArtists)
    private val daysTotal: TextView = view.findViewById(R.id.statsDaysTotal)
    private val dayBars: DayBarsView = view.findViewById(R.id.statsDays)
    private val daysFirst: TextView = view.findViewById(R.id.statsDaysFirst)
    private val appRows: LinearLayout = view.findViewById(R.id.statsApps)
    private val songRows: LinearLayout = view.findViewById(R.id.statsSongs)
    private val artistRows: LinearLayout = view.findViewById(R.id.statsArtists)
    private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    /** A day in the bubble of the chart, with its weekday ("sáb 31 oct"). */
    private val bubbleDayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    /** Counts and ranks with the digits and separators of the phone's language ("1,234"). */
    private val numbers = NumberFormat.getIntegerInstance()

    private var period = StatsPeriod.WEEK
    private var loading: Job? = null

    /** What each part shows now, to draw it again only when it changes. */
    private var shown: HistoryStats? = null

    init {
        dayBars.describe = { day -> dayName(day.date) to dayTime(day.playedMs) }
        val chips = mapOf(
            R.id.periodWeek to StatsPeriod.WEEK,
            R.id.periodDays30 to StatsPeriod.DAYS_30,
            R.id.periodMonth to StatsPeriod.THIS_MONTH,
            R.id.periodAll to StatsPeriod.ALL,
        )
        view.findViewById<ChipGroup>(R.id.statsPeriods).apply {
            check(chips.entries.first { it.value == period }.key)
            setOnCheckedStateChangeListener { _, checked ->
                period = chips[checked.firstOrNull()] ?: return@setOnCheckedStateChangeListener
                load()
            }
        }
        load()
    }

    /** Reads the statistics of the period again and shows what changed. */
    fun load() {
        loading?.cancel()
        loading = scope.launch { show(history.stats(period)) }
    }

    private fun show(stats: HistoryStats) {
        val before = shown
        shown = stats
        val nothing = stats.plays == 0 && stats.apps.isEmpty()
        empty.isVisible = nothing
        body.isVisible = !nothing
        if (nothing) return

        listened.text = duration(stats.listenedMs)
        plays.text = numbers.format(stats.plays)
        songs.text = numbers.format(stats.songs)
        artists.text = numbers.format(stats.artists)
        if (before?.days != stats.days) showDays(stats)
        if (before?.apps != stats.apps) showApps(stats.apps)
        if (before?.topSongs != stats.topSongs) showSongs(stats.topSongs)
        if (before?.topArtists != stats.topArtists) showArtists(stats.topArtists)
    }

    private fun showDays(stats: HistoryStats) {
        val total = stats.days.sumOf { it.playedMs }
        daysTotal.text = context.getString(R.string.stats_days_total, duration(total))
        daysFirst.text = stats.days.firstOrNull()?.date?.let(dayFormat::format).orEmpty()
        dayBars.setDays(stats.days)
        dayBars.contentDescription = context.getString(
            R.string.stats_days_description,
            duration(total),
            duration(stats.days.maxOfOrNull { it.playedMs } ?: 0),
        )
    }

    /** One row per app, with a bar of its share of the longest: the primary color for music. */
    private fun showApps(usages: List<AppUsage>) {
        appRows.removeAllViews()
        val longest = usages.maxOfOrNull { it.playedMs } ?: 0
        val muted = ContextCompat.getColor(context, R.color.on_surface_muted)
        val primary = MaterialColors.getColor(appRows, androidx.appcompat.R.attr.colorPrimary)
        for (usage in usages) {
            val row = inflater.inflate(R.layout.item_stat_app, appRows, false)
            val name = apps.label(usage.source)
            row.findViewById<ImageView>(R.id.statAppIcon).setImageDrawable(apps.icon(usage.source))
            row.findViewById<TextView>(R.id.statAppName).text = when (usage.kind) {
                AppKind.MUSIC -> name
                AppKind.VIDEO -> context.getString(R.string.stats_app_video, name)
                AppKind.OTHER -> context.getString(R.string.stats_app_other, name)
            }
            row.findViewById<LinearProgressIndicator>(R.id.statAppBar).apply {
                setIndicatorColor(if (usage.kind == AppKind.MUSIC) primary else muted)
                progress = StatsFormat.share(usage.playedMs, longest, BAR_SCALE)
            }
            row.findViewById<TextView>(R.id.statAppTime).text = duration(usage.playedMs)
            appRows.addView(row)
        }
        if (usages.isEmpty()) appRows.addView(none(appRows))
    }

    /** The ranking of songs, with their covers; touching one opens it. */
    private fun showSongs(top: List<SongStat>) {
        songRows.removeAllViews()
        top.forEachIndexed { index, stat ->
            val row = inflater.inflate(R.layout.item_stat_song, songRows, false)
            val entry = stat.entry
            row.findViewById<TextView>(R.id.statSongRank).text = numbers.format(index + 1)
            row.findViewById<TextView>(R.id.statSongTitle).text = entry.title
            row.findViewById<TextView>(R.id.statSongArtist).text = artistLine(entry)
            row.findViewById<TextView>(R.id.statSongPlays).text = times(stat.plays)
            showCover(row.findViewById(R.id.statSongCover), entry.coverRef)
            row.setOnClickListener { onOpen(entry) }
            songRows.addView(row)
        }
        if (top.isEmpty()) songRows.addView(none(songRows))
    }

    private fun showArtists(top: List<ArtistStat>) {
        artistRows.removeAllViews()
        top.forEachIndexed { index, stat ->
            val row = inflater.inflate(R.layout.item_stat_artist, artistRows, false)
            row.findViewById<TextView>(R.id.statArtistRank).text = numbers.format(index + 1)
            row.findViewById<TextView>(R.id.statArtistName).text = stat.artist
            row.findViewById<TextView>(R.id.statArtistPlays).text = times(stat.plays)
            artistRows.addView(row)
        }
        if (top.isEmpty()) artistRows.addView(none(artistRows))
    }

    /** The cover at [ref], from memory at once or fetched; the placeholder meanwhile. */
    private fun showCover(cover: ImageView, ref: String?) {
        val cached = ref?.let(thumbnails::cached)
        if (cached != null) {
            cover.setImageBitmap(cached)
            return
        }
        cover.setImageResource(R.drawable.cover_placeholder)
        if (ref == null) return
        scope.launch { thumbnails.load(ref)?.let(cover::setImageBitmap) }
    }

    /** The artist, and "vídeo" for a video played in a music app. */
    private fun artistLine(entry: HistoryEntry): String {
        val artist = entry.artist.orEmpty()
        return if (entry.kind == SongKind.VIDEO) context.getString(R.string.stats_video_artist, artist) else artist
    }

    /** "Hoy", "Ayer", or the weekday and date, for the bubble of the chart. */
    private fun dayName(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> context.getString(R.string.history_today)
            today.minusDays(1) -> context.getString(R.string.history_yesterday)
            else -> bubbleDayFormat.format(date).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    /** The music of a day for the bubble of the chart: its time, or that it had none. */
    private fun dayTime(ms: Long): String = when {
        ms <= 0 -> context.getString(R.string.stats_day_none)
        StatsFormat.hoursAndMinutes(ms) == (0L to 0L) -> context.getString(R.string.stats_day_under_minute)
        else -> duration(ms)
    }

    private fun times(count: Int): String = context.resources.getQuantityString(R.plurals.stats_times, count, count)

    private fun duration(ms: Long): String {
        val (hours, minutes) = StatsFormat.hoursAndMinutes(ms)
        return when {
            hours == 0L -> context.getString(R.string.stats_minutes, minutes)
            minutes == 0L -> context.getString(R.string.stats_hours, hours)
            else -> context.getString(R.string.stats_hours_minutes, hours, minutes)
        }
    }

    /** "Nothing in this period", for an empty list. */
    private fun none(parent: ViewGroup): TextView = TextView(context).apply {
        setText(R.string.stats_none)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.on_surface_muted)))
        setPadding(0, (NONE_PADDING_DP * parent.resources.displayMetrics.density).toInt(), 0, 0)
    }

    private companion object {
        /** Steps of the bars of the apps. */
        const val BAR_SCALE = 1000

        /** Space above "nothing in this period". */
        const val NONE_PADDING_DP = 8
    }
}
