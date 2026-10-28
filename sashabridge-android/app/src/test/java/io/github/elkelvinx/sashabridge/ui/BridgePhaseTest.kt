package io.github.elkelvinx.sashabridge.ui

import io.github.elkelvinx.sashabridge.bridge.BridgeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The screen shows what the app is really doing, in order of what the user must fix first. */
class BridgePhaseTest {

    /** Without notification access nothing else matters. */
    @Test
    fun missingAccessComesFirst() {
        assertEquals(BridgePhase.NoAccess, BridgePhase.of(false, true, true, BridgeStatus.Listening))
        assertEquals(BridgePhase.NoAccess, BridgePhase.of(false, false, false, BridgeStatus.Stopped))
    }

    /** A bridge the user turned off is shown as off, even while the server is still stopping. */
    @Test
    fun disabledBridgeIsOff() {
        assertEquals(BridgePhase.Disabled, BridgePhase.of(true, true, false, BridgeStatus.Listening))
        assertEquals(BridgePhase.Disabled, BridgePhase.of(true, false, false, BridgeStatus.Stopped))
    }

    /** With access but no bound listener, the app waits for Android. */
    @Test
    fun unboundListenerIsWaiting() {
        assertEquals(BridgePhase.WaitingForListener, BridgePhase.of(true, false, true, BridgeStatus.Stopped))
    }

    /** Once bound and on, the server state decides. */
    @Test
    fun serverStateDecidesWhenEverythingIsReady() {
        assertEquals(BridgePhase.Starting, BridgePhase.of(true, true, true, BridgeStatus.Stopped))
        assertEquals(BridgePhase.Listening, BridgePhase.of(true, true, true, BridgeStatus.Listening))
        assertEquals(BridgePhase.Connected(2), BridgePhase.of(true, true, true, BridgeStatus.ClientConnected(2)))
        assertEquals(BridgePhase.Failed("busy"), BridgePhase.of(true, true, true, BridgeStatus.Error("busy")))
    }
}
