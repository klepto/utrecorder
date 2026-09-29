package dev.klepto.utrecorder.server

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Long-running part of the server.
 */
interface Service {
    /**
     * Returns `true` while the service is running. By default, returns `true` until the calling coroutine is cancelled.
     */
    suspend fun isRunning(): Boolean = currentCoroutineContext().isActive

    /**
     * Starts the service.
     */
    suspend fun start()
}
