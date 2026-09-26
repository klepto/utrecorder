package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.recording.Recording

/**
 * Finder of new demos, which stores each as a [Demo] with a [Recording] and enqueues the recording.
 *
 * The UTBT fetcher is [UtbtDemoFetcher].
 */
interface DemoFetcher {
    /**
     * Fetches new demos until cancelled.
     */
    suspend fun run(): Nothing
}
