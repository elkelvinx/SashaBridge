package io.github.elkelvinx.sashabridge.protocol

import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Encoder of [MediaSnapshot] in the protocol v1 format, for one PC session.
 *
 * It lets the new media pipeline feed the current PC client, which only understands v1,
 * until the server switches to protocol v2 in phase 5. It is removed then.
 *
 * The v1 client keeps the last album art it received and v1 has no art identifier, so the
 * art is included in the first message of the session and whenever its identifier changes.
 * The previous code marked the art as sent before checking that it existed, so art that
 * arrived after the title was never sent (R3).
 *
 * Not thread-safe: create one instance per session and use it from that session's coroutine.
 */
class LegacyV1Session {

    /** Identifier of the last art sent in this session. */
    private var lastSentArtId: String? = null

    /**
     * Encodes [snapshot] as a v1 media message.
     *
     * @param snapshot State to send.
     * @param nowElapsedMs Current `SystemClock.elapsedRealtime()`, used to extrapolate the position.
     * @return The JSON text of the frame.
     */
    fun encode(snapshot: MediaSnapshot, nowElapsedMs: Long): String {
        val art = snapshot.art?.takeIf { it.artId != lastSentArtId }
        val message = buildJsonObject {
            put("title", snapshot.title)
            put("artist", snapshot.artist)
            // v1 uses 0 for an unknown duration.
            put("duration", snapshot.durationMs ?: 0L)
            put("isPlaying", snapshot.isPlaying)
            put("position", snapshot.positionAt(nowElapsedMs))
            if (art != null) put("albumArt", Base64.getEncoder().encodeToString(art.jpeg))
        }
        if (art != null) lastSentArtId = art.artId
        return message.toString()
    }
}
