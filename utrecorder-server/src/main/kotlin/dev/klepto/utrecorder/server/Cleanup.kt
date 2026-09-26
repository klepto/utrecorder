package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.logger
import kotlinx.coroutines.delay
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

/**
 * A file or directory waiting to be deleted.
 *
 * @property path The absolute path to delete.
 * @property attempts The number of deletions tried.
 * @property error The message of the last failed deletion, or `null` if none failed.
 */
data class Cleanup(
    val path: Path,
    val attempts: Int = 0,
    val error: String? = null,
)

/**
 * Deletes files and directories through [store], persisting every deletion so a failed one is retried.
 *
 * A deletion made outside [CleanupService] risks being lost to a crash or a restart before it completes. Every
 * deletion the server makes goes through [delete] instead, so the pending path survives in [store] until it succeeds.
 */
class CleanupService(
    private val store: DemoStore,
) {
    /**
     * Persists [path] as a pending cleanup, then deletes it. A file is removed directly, a directory recursively. A
     * path that no longer exists counts as success. A failed deletion keeps its row in [store], with [Cleanup.attempts]
     * incremented and [Cleanup.error] set to the failure's message; the failure is logged, not thrown.
     */
    suspend fun delete(path: Path) {
        val cleanup = Cleanup(path)
        store.put(cleanup)
        attempt(cleanup)
    }

    /**
     * Retries every pending cleanup of [store], with the same success and failure handling as [delete].
     */
    suspend fun retry() {
        store.load().cleanups.forEach { attempt(it) }
    }

    /**
     * Runs [retry], then every hour again, until cancelled.
     */
    suspend fun loop() {
        while (true) {
            retry()
            delay(RETRY_INTERVAL)
        }
    }

    private suspend fun attempt(cleanup: Cleanup) {
        cleanup.path
            .deleteCatching()
            .onSuccess { store.remove(cleanup.path) }
            .onFailure { failure ->
                store.put(cleanup.copy(attempts = cleanup.attempts + 1, error = failure.message))
                log.warn(failure) { "Failed to delete ${cleanup.path}" }
            }
    }

    companion object {
        private val RETRY_INTERVAL = 1.hours
        private val log = logger<CleanupService>()
    }
}
