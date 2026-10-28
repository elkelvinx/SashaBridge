package io.github.elkelvinx.sashabridge.ui

import io.github.elkelvinx.sashabridge.bridge.BridgeStatus

/**
 * What the main screen tells the user about the bridge, derived from the real state of the
 * app so that the screen never disagrees with the service (A7).
 *
 * Immutable; can be shared between threads.
 */
sealed interface BridgePhase {

    /** Notification access is not granted, so the bridge cannot read the players. */
    data object NoAccess : BridgePhase

    /** The user turned the bridge off. */
    data object Disabled : BridgePhase

    /** Access is granted but Android has not bound the listener yet. */
    data object WaitingForListener : BridgePhase

    /** The bridge is on and the server is about to listen. */
    data object Starting : BridgePhase

    /** The server listens and no PC is connected. */
    data object Listening : BridgePhase

    /**
     * At least one PC is connected.
     *
     * @property clients Number of connected PCs.
     */
    data class Connected(val clients: Int) : BridgePhase

    /**
     * The server cannot run.
     *
     * @property reason Technical description, shown as a detail.
     */
    data class Failed(val reason: String) : BridgePhase

    companion object {

        /**
         * Derives the phase. Pure; any thread.
         *
         * Missing access comes first because nothing works without it, and a disabled bridge
         * comes next because the user chose it.
         *
         * @param hasAccess Whether notification access is granted.
         * @param listenerConnected Whether Android has bound the listener.
         * @param enabled Whether the user wants the bridge on.
         * @param status State of the server.
         * @return The phase to show.
         */
        fun of(
            hasAccess: Boolean,
            listenerConnected: Boolean,
            enabled: Boolean,
            status: BridgeStatus,
        ): BridgePhase = when {
            !hasAccess -> NoAccess
            !enabled -> Disabled
            !listenerConnected -> WaitingForListener
            else -> when (status) {
                BridgeStatus.Stopped -> Starting
                BridgeStatus.Listening -> Listening
                is BridgeStatus.ClientConnected -> Connected(status.clients)
                is BridgeStatus.Error -> Failed(status.reason)
            }
        }
    }
}
