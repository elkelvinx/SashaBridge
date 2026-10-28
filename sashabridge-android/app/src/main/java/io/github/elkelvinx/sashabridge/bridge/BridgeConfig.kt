package io.github.elkelvinx.sashabridge.bridge

/** Constants shared by the whole app. */
object BridgeConfig {

    /** TCP port of the WebSocket server; the PC reaches it through `adb forward`. */
    const val PORT: Int = 8080

    /**
     * Address the server listens on. `adb forward` connects from inside the phone, so the
     * loopback address is enough, and the port is not reachable from the network (A6).
     */
    const val HOST: String = "127.0.0.1"

    /** Logcat tag of every component, so that `adb logcat -s SashaBridge` shows the whole bridge. */
    const val LOG_TAG: String = "SashaBridge"
}
