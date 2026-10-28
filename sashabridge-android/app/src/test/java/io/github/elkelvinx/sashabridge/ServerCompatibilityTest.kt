package io.github.elkelvinx.sashabridge

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.junit.Test

/**
 * Guards the combination of Ktor and kotlinx-coroutines that the app resolves.
 *
 * Ktor 2.x calls internal coroutine APIs, so a newer kotlinx-coroutines can make the server
 * fail with NoSuchMethodError as soon as the service creates it. The unit tests run with the
 * same resolved versions as the app, so creating the engine here reproduces that failure
 * before installing.
 */
class ServerCompatibilityTest {

    /** The WebSocket server engine can be created; it is not started. */
    @Test
    fun serverEngineCanBeCreated() {
        embeddedServer(Netty, port = 0) {}
    }
}
