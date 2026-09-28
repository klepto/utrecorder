package dev.klepto.utrecorder.server

/**
 * Long-running part of the server.
 */
interface Service {
    /**
     * `true` while the service is running. Always `true` by default.
     */
    val isRunning: Boolean get() = true

    /**
     * Starts the service.
     */
    suspend fun start()

    /**
     * Stops the service. Does nothing by default.
     */
    suspend fun stop() {}
}
