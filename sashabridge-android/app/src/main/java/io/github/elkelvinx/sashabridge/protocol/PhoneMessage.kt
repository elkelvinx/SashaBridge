package io.github.elkelvinx.sashabridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Message from the phone to the PC (protocol v2).
 *
 * The specification is `docs/protocolo-v2.md`. On the wire each subtype is identified by
 * the `type` field, whose value is the subtype's [SerialName]. Constraints that the types
 * cannot express are checked in `init` blocks, which also run during deserialization, so an
 * invalid message never becomes an instance.
 *
 * All subtypes are immutable and can be shared between threads.
 */
@Serializable
sealed interface PhoneMessage {

    /**
     * First message of every session.
     *
     * @property protocol Protocol version of the phone app.
     * @property device Phone model ([android.os.Build.MODEL]), for logging only.
     * @throws IllegalArgumentException if [protocol] is lower than 1.
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocol: Int,
        val device: String,
    ) : PhoneMessage {
        init {
            require(protocol >= 1) { "protocol must be at least 1, was $protocol" }
        }
    }

    /**
     * Album art image, referenced by [MediaState.artId].
     *
     * @property artId Opaque identifier; equal identifiers always carry equal bytes.
     * @property mime Media type of the image, currently always `image/jpeg`.
     * @property data Image bytes in standard Base64, without line breaks.
     */
    @Serializable
    @SerialName("media.art")
    data class MediaArt(
        val artId: String,
        val mime: String,
        val data: String,
    ) : PhoneMessage

    /**
     * Full state of the active player. Each message replaces the previous one.
     *
     * @property trackId Opaque identifier that changes when the track changes.
     * @property source Android package of the player, such as `com.spotify.music`.
     * @property title Track title, or `null` if the player does not report it.
     * @property artist Track artist without the playback context, or `null`.
     * @property context Playback context the player attaches to the artist, such as
     *   "Del Modo aleatorio inteligente", or `null`. Language-dependent: for display only.
     * @property album Album name, or `null` if the player does not report it.
     * @property durationMs Track length in milliseconds, or `null` if unknown.
     * @property positionMs Playback position in milliseconds when the message is sent.
     * @property isPlaying Whether the player is in the playing state.
     * @property playbackSpeed Playback speed: `1.0` is normal speed and `0.0` a pause.
     * @property shuffle Shuffle mode, or `null` if the bridge cannot control it for this player.
     * @property artId Album art of the track, or `null` if the track has none.
     * @throws IllegalArgumentException if [durationMs] is not positive or [positionMs] is negative.
     */
    @Serializable
    @SerialName("media.state")
    data class MediaState(
        val trackId: String,
        val source: String,
        val title: String? = null,
        val artist: String? = null,
        val context: String? = null,
        val album: String? = null,
        val durationMs: Long? = null,
        val positionMs: Long,
        val isPlaying: Boolean,
        val playbackSpeed: Float,
        val shuffle: ShuffleMode? = null,
        val artId: String? = null,
    ) : PhoneMessage {
        init {
            require(durationMs == null || durationMs > 0) {
                "durationMs must be positive or null, was $durationMs"
            }
            require(positionMs >= 0) { "positionMs must not be negative, was $positionMs" }
        }
    }

    /** No player is active; the PC clears its media overlay. */
    @Serializable
    @SerialName("media.cleared")
    data object MediaCleared : PhoneMessage

    /**
     * State of the annotation screen, sent on the `/pen` route after `hello` and on every change.
     *
     * @property armed Whether the screen is open and waiting, so that the PC sends the captures
     *   the user copies without being asked.
     */
    @Serializable
    @SerialName("annotate.status")
    data class AnnotateStatus(val armed: Boolean) : PhoneMessage

    /**
     * A capture annotated on the phone.
     *
     * @property requestId Identifier of the capture it came from.
     * @property mime Media type of the image, currently always `image/png`.
     * @property width Width of the image in pixels.
     * @property height Height of the image in pixels.
     * @property data Image bytes in standard Base64, without line breaks.
     * @throws IllegalArgumentException if [requestId] is empty or a dimension is not positive.
     */
    @Serializable
    @SerialName("annotate.result")
    data class AnnotateResult(
        val requestId: String,
        val mime: String,
        val width: Int,
        val height: Int,
        val data: String,
    ) : PhoneMessage {
        init {
            requireValidImage(requestId, width, height)
        }
    }

    /**
     * The user discarded a capture.
     *
     * @property requestId Identifier of the discarded capture.
     * @throws IllegalArgumentException if [requestId] is empty.
     */
    /**
     * An image the phone sends on its own, answering no capture: one shared to SashaBridge
     * from another app, annotated or not. The PC puts it on its clipboard.
     *
     * @property mime Media type of the image, currently always `image/png`.
     * @property width Width of the image in pixels.
     * @property height Height of the image in pixels.
     * @property data Image bytes in standard Base64, without line breaks.
     * @throws IllegalArgumentException if a dimension is not positive.
     */
    @Serializable
    @SerialName("annotate.shared")
    data class AnnotateShared(
        val mime: String,
        val width: Int,
        val height: Int,
        val data: String,
    ) : PhoneMessage {
        init {
            require(width > 0 && height > 0) { "image size must be positive, was ${width}x$height" }
        }
    }

    @Serializable
    @SerialName("annotate.cancelled")
    data class AnnotateCancelled(val requestId: String) : PhoneMessage {
        init {
            require(requestId.isNotEmpty()) { "requestId must not be empty" }
        }
    }

    /**
     * State of the tablet screen, sent on the `/pen` route after `hello` and on every change.
     *
     * @property active Whether the screen is open; when it closes, the PC lifts its pen.
     */
    @Serializable
    @SerialName("tablet.status")
    data class TabletStatus(val active: Boolean) : PhoneMessage

    /**
     * Asks the PC to map the tablet to another monitor.
     *
     * @property monitor Index of the monitor, from 0, as in [PcMessage.TabletTarget].
     * @throws IllegalArgumentException if [monitor] is negative.
     */
    @Serializable
    @SerialName("tablet.select")
    data class TabletSelect(val monitor: Int) : PhoneMessage {
        init {
            require(monitor >= 0) { "monitor must not be negative" }
        }
    }

    /**
     * Measures the round trip to the PC, which answers [PcMessage.TabletPong] at once.
     *
     * @property timeMs Phone uptime when sent, from 0 to 2^32 - 1, wrapping like the pen samples.
     * @throws IllegalArgumentException if [timeMs] is outside that range.
     */
    @Serializable
    @SerialName("tablet.ping")
    data class TabletPing(val timeMs: Long) : PhoneMessage {
        init {
            requireUptime(timeMs)
        }
    }
}

/**
 * Checks a `timeMs` of the tablet's round trip.
 *
 * @throws IllegalArgumentException if [timeMs] is not between 0 and 2^32 - 1.
 */
internal fun requireUptime(timeMs: Long) {
    require(timeMs in 0..MAX_UPTIME_MS) { "timeMs must be between 0 and $MAX_UPTIME_MS" }
}

/** Largest `timeMs`: it wraps around at 2^32 like the pen samples' time. */
internal const val MAX_UPTIME_MS = 0xFFFF_FFFFL

/**
 * Checks the fields that every annotation image shares.
 *
 * @throws IllegalArgumentException if [requestId] is empty or a dimension is not positive.
 */
internal fun requireValidImage(requestId: String, width: Int, height: Int) {
    require(requestId.isNotEmpty()) { "requestId must not be empty" }
    require(width > 0 && height > 0) { "image size must be positive, was ${width}x$height" }
}

/** Values of the `shuffle` field of [PhoneMessage.MediaState]. */
@Serializable
enum class ShuffleMode {
    /** Tracks play in order. */
    OFF,

    /** Tracks play in random order. */
    ON,

    /** Spotify's smart shuffle: random order with recommended tracks mixed in. */
    SMART,
}
