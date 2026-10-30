package io.github.elkelvinx.sashabridge.history

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri

/**
 * Opens a track of the history in its player, as close to the track as the player allows:
 * - Spotify: the track itself, from its `spotify:track:` id.
 * - YouTube Music, which gives no id: its search for the title and the artist.
 * - Other players: Android's "play from search", which most music apps answer.
 * - Failing all that, the player's main screen.
 *
 * Main thread.
 */
internal object PlayerLinks {

    private const val SPOTIFY = "com.spotify.music"
    private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

    /** YouTube Music's search page; the app opens it instead of the browser. */
    private const val YOUTUBE_MUSIC_SEARCH = "https://music.youtube.com/search?q="

    /**
     * Opens [entry] in its player.
     *
     * @return Whether some screen of the player opened.
     */
    fun open(context: Context, entry: HistoryEntry): Boolean = intentsFor(context, entry).any { intent ->
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** The ways to open [entry], best first. */
    private fun intentsFor(context: Context, entry: HistoryEntry): List<Intent> {
        val query = listOfNotNull(entry.title, entry.artist).joinToString(" ")
        val mediaId = entry.mediaId
        return buildList {
            if (entry.source == SPOTIFY && mediaId != null && mediaId.startsWith("spotify:")) {
                add(Intent(Intent.ACTION_VIEW, mediaId.toUri()).setPackage(SPOTIFY))
            }
            if (entry.source == YOUTUBE_MUSIC && query.isNotEmpty()) {
                add(Intent(Intent.ACTION_VIEW, (YOUTUBE_MUSIC_SEARCH + Uri.encode(query)).toUri()).setPackage(YOUTUBE_MUSIC))
            }
            if (query.isNotEmpty()) {
                add(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(entry.source).putExtra(SearchManager.QUERY, query))
            }
            context.packageManager.getLaunchIntentForPackage(entry.source)?.let(::add)
        }
    }
}
