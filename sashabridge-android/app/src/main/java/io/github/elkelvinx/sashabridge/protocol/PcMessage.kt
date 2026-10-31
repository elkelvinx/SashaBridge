package io.github.elkelvinx.sashabridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Message from the PC to the phone (protocol v2).
 *
 * See [PhoneMessage] for how the `type` field and the validation work. All subtypes are
 * immutable and can be shared between threads.
 */
@Serializable
sealed interface PcMessage {

    /**
     * First message of every session.
     *
     * @property protocol Protocol version of the PC client.
     * @property client Client identification as `name/version`, for logging only.
     * @throws IllegalArgumentException if [protocol] is lower than 1.
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocol: Int,
        val client: String,
    ) : PcMessage {
        init {
            require(protocol >= 1) { "protocol must be at least 1, was $protocol" }
        }
    }

    /**
     * Order for the active player.
     *
     * @property action Requested action.
     * @property positionMs Target position in milliseconds; required with
     *   [CommandAction.SEEK] and `null` with every other action.
     * @throws IllegalArgumentException if [positionMs] does not match [action], or is negative.
     */
    @Serializable
    @SerialName("cmd")
    data class Command(
        val action: CommandAction,
        val positionMs: Long? = null,
    ) : PcMessage {
        init {
            if (action == CommandAction.SEEK) {
                require(positionMs != null && positionMs >= 0) {
                    "SEEK requires a positionMs of at least 0, was $positionMs"
                }
            } else {
                require(positionMs == null) { "$action does not accept positionMs" }
            }
        }
    }

    /**
     * A capture for the phone to annotate, sent on the `/pen` route.
     *
     * @property requestId Identifier the PC gives the capture; the result carries it back.
     * @property mime Media type of the image, currently always `image/png`.
     * @property width Width of the image in pixels.
     * @property height Height of the image in pixels.
     * @property data Image bytes in standard Base64, without line breaks.
     * @throws IllegalArgumentException if [requestId] is empty or a dimension is not positive.
     */
    @Serializable
    @SerialName("annotate.request")
    data class AnnotateRequest(
        val requestId: String,
        val mime: String,
        val width: Int,
        val height: Int,
        val data: String,
    ) : PcMessage {
        init {
            requireValidImage(requestId, width, height)
        }
    }

    /**
     * Where the tablet draws: one of the PC's monitors. Sent on the `/pen` route after `hello`
     * and whenever the monitor changes, so the phone gives its drawing area the same shape.
     *
     * @property monitor Index of the monitor, from 0.
     * @property count Number of monitors, to cycle through them with [PhoneMessage.TabletSelect].
     * @property name Name Windows gives the monitor, for display.
     * @property width Width of the monitor in pixels.
     * @property height Height of the monitor in pixels.
     * @throws IllegalArgumentException if [monitor] is not below [count] or a size is not positive.
     */
    @Serializable
    @SerialName("tablet.target")
    data class TabletTarget(
        val monitor: Int,
        val count: Int,
        val name: String,
        val width: Int,
        val height: Int,
    ) : PcMessage {
        init {
            require(monitor in 0 until count) { "monitor must be between 0 and count - 1" }
            require(width > 0 && height > 0) { "width and height must be positive" }
        }
    }

    /**
     * Answer to [PhoneMessage.TabletPing], sent at once.
     *
     * @property timeMs The `timeMs` of the ping it answers.
     * @throws IllegalArgumentException if [timeMs] is not between 0 and 2^32 - 1.
     */
    @Serializable
    @SerialName("tablet.pong")
    data class TabletPong(val timeMs: Long) : PcMessage {
        init {
            requireUptime(timeMs)
        }
    }
}

/**
 * Values of the `action` field of [PcMessage.Command].
 *
 * [PLAY] and [PAUSE] are explicit: repeating [PAUSE] on a paused player does not resume it.
 * [TOGGLE] serves the play/pause keys that do not say which state they want.
 */
@Serializable
enum class CommandAction {
    /** Starts playback; does nothing if already playing. */
    PLAY,

    /** Pauses playback; does nothing if already paused. */
    PAUSE,

    /** Switches between playing and paused. */
    TOGGLE,

    /** Skips to the next track. */
    NEXT,

    /** Goes back to the previous track. */
    PREVIOUS,

    /** Moves the playback position to [PcMessage.Command.positionMs]. */
    SEEK,

    /**
     * Presses the player's own shuffle button, which moves to its next shuffle mode
     * (in Spotify, OFF, then ON, then SMART, then OFF again).
     */
    TOGGLE_SHUFFLE,

    /**
     * Presses the player's own like button: likes the track, or takes the like back if it
     * already has it.
     */
    TOGGLE_LIKE,

    /**
     * Pauses every player but the music one and shows the last music track, paused, instead
     * of the video that was playing. The PC then brings SashaBridge to the front itself.
     */
    HIDE,

    /**
     * Goes back to what [HIDE] hid: opens the video's app where it was and resumes it. The
     * PC sends it instead of [HIDE] while the phone reports [PhoneMessage.HideStatus.hidden].
     */
    UNHIDE,
}
