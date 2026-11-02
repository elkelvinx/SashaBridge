package io.github.elkelvinx.sashabridge.history

import android.content.pm.ApplicationInfo

/** What an app plays, which decides what the history and the statistics keep of it. */
enum class AppKind {

    /** Songs: each play goes to the history, and its time to the statistics. */
    MUSIC,

    /** Videos (YouTube, Netflix...): only the time goes to the statistics. */
    VIDEO,

    /** Anything else that plays, such as a web page in a browser: only the time. */
    OTHER,
}

/** What a song of the history is. */
enum class SongKind {

    /** A track of an album or a single. */
    SONG,

    /** A video played in a music app, such as a fan upload in YouTube Music. */
    VIDEO,
}

/**
 * Sorts the players. Known apps first; for the rest, the category the app declares to
 * Android (`android:appCategory`); and an unknown player with no category counts as music,
 * as every player did before.
 *
 * Pure and thread-safe.
 */
internal object AppKinds {

    private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

    private val MUSIC_APPS = setOf(
        "com.spotify.music",
        YOUTUBE_MUSIC,
        "iad1tya.echo.music",
        "deezer.android.app",
        "com.aspiro.tidal",
        "com.apple.android.music",
        "com.amazon.mp3",
        "com.soundcloud.android",
        "com.sec.android.app.music",
        "com.maxmpz.audioplayer",
    )

    private val VIDEO_APPS = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "com.disney.disneyplus",
        "com.wbd.stream",
        "tv.twitch.android.app",
        "com.crunchyroll.crunchyroid",
    )

    private val BROWSERS = setOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.brave.browser",
    )

    /**
     * What [source] plays.
     *
     * @param category The app's `ApplicationInfo.category`, or `null` if unknown.
     */
    fun of(source: String, category: Int? = null): AppKind = when {
        source in MUSIC_APPS -> AppKind.MUSIC
        source in VIDEO_APPS -> AppKind.VIDEO
        source in BROWSERS -> AppKind.OTHER
        category == ApplicationInfo.CATEGORY_VIDEO -> AppKind.VIDEO
        category == ApplicationInfo.CATEGORY_AUDIO -> AppKind.MUSIC
        else -> AppKind.MUSIC
    }

    /**
     * What a play of [source] is: YouTube Music gives every track of an album or a single its
     * album, and none to the videos it plays (uploads, music videos).
     */
    fun songKind(source: String, album: String?): SongKind =
        if (source == YOUTUBE_MUSIC && album.isNullOrBlank()) SongKind.VIDEO else SongKind.SONG
}
