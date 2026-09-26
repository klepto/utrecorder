@file:UseSerializers(PathSerializer::class)

package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.database.PathSerializer
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

/**
 * A file or directory waiting to be deleted.
 *
 * @property path The absolute path to delete.
 * @property attempts The number of deletions tried.
 * @property error The message of the last failed deletion, or `null` if none failed.
 */
@Serializable
data class Cleanup(
    val path: Path,
    val attempts: Int = 0,
    val error: String? = null,
)

/**
 * Deletes files and directories, persisting every deletion to [cleanups] so a failed one is retried.
 *
 * A deletion made outside [CleanupService] risks being lost to a crash or a restart before it completes. Every
 * deletion the server makes goes through [delete] instead, so the pending path survives in [cleanups] until it
 * succeeds.
 *
 * @property cleanups The collection of pending cleanups, keyed by the string form of [Cleanup.path].
 */
class CleanupService(
    private val cleanups: DocumentStore.Collection<Cleanup>,
) {
    /**
     * Persists [path] as a pending cleanup, then deletes it. A file is removed directly, a directory recursively. A
     * path that no longer exists counts as success. A failed deletion keeps its cleanup in [cleanups], with [Cleanup.attempts]
     * incremented and [Cleanup.error] set to the failure's message; the failure is logged, not thrown.
     */
    suspend fun delete(path: Path) {
        val cleanup = Cleanup(path)
        cleanups.put(cleanup)
        attempt(cleanup)
    }

    /**
     * Retries every pending cleanup of [cleanups], with the same success and failure handling as [delete].
     */
    suspend fun retry() {
        cleanups.all().forEach { attempt(it) }
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
            .onSuccess { cleanups.remove(cleanup.path.toString()) }
            .onFailure { failure ->
                cleanups.put(cleanup.copy(attempts = cleanup.attempts + 1, error = failure.message))
                log.warn(failure) { "Failed to delete ${cleanup.path}" }
            }
    }

    companion object {
        private val RETRY_INTERVAL = 1.hours
        private val log = logger<CleanupService>()
    }
}
