package io.github.elkelvinx.sashabridge.media

import io.github.elkelvinx.sashabridge.protocol.PcMessage

/**
 * Command for the active player from the app's own screen.
 *
 * The screen can do everything the PC can ([Transport]) and a few things the protocol does not
 * carry yet, such as repeat and the play queue.
 *
 * Immutable; can be shared between threads.
 */
sealed interface PlayerCommand {

    /**
     * A command the PC could also send.
     *
     * @property command The protocol command.
     */
    data class Transport(val command: PcMessage.Command) : PlayerCommand

    /** Presses the player's own repeat button, which moves to its next repeat mode. */
    data object ToggleRepeat : PlayerCommand

    /**
     * Jumps to an item of the player's queue.
     *
     * @property queueId [QueueEntry.queueId] of the item.
     */
    data class SkipToQueueItem(val queueId: Long) : PlayerCommand
}
