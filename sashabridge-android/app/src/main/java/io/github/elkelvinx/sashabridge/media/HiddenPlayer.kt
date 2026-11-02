package io.github.elkelvinx.sashabridge.media

import io.github.elkelvinx.sashabridge.history.HistoryEntry

/**
 * What the card and the PC show while a video is hidden and the music player has no session
 * any more: the newest track of the history, paused.
 *
 * Pure and thread-safe.
 */
internal object HiddenPlayer {

    /**
     * [entry] as a paused player. Its [MediaSnapshot.trackId] is the one the player gave the
     * track, so the card does not take it for a new one.
     *
     * @param entry The newest track of the history.
     * @param art Its cover, or `null` if it has none.
     */
    fun snapshotOf(entry: HistoryEntry, art: AlbumArt?): MediaSnapshot = MediaSnapshot(
        trackId = MediaSnapshot.trackIdOf(entry.source, entry.mediaId, entry.title, entry.artist, entry.album, null),
        source = entry.source,
        title = entry.title,
        artist = entry.artist,
        playbackContext = null,
        album = entry.album,
        durationMs = null,
        isPlaying = false,
        reportedPositionMs = PlaybackPositionCalculator.UNKNOWN_POSITION,
        positionUpdateTimeMs = 0L,
        playbackSpeed = 0f,
        shuffle = null,
        art = art,
        mediaId = entry.mediaId,
    )
}
