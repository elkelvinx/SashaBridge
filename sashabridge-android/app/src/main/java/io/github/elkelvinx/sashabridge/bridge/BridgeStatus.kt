package io.github.elkelvinx.sashabridge.bridge

/**
 * State of the WebSocket server, as shown to the user.
 *
 * Immutable; can be shared between threads.
 */
sealed interface BridgeStatus {

    /** The server is not running: the bridge is disabled or the listener is not connected. */
    data object Stopped : BridgeStatus

    /** The server is listening and no PC is connected. */
    data object Listening : BridgeStatus

    /**
     * The server is listening and at least one PC is connected.
     *
     * @property clients Number of open sessions, at least 1.
     */
    data class ClientConnected(val clients: Int) : BridgeStatus

    /**
     * The server could not start or failed while running.
     *
     * @property reason Description of the problem, for the log and the UI.
     */
    data class Error(val reason: String) : BridgeStatus
}
