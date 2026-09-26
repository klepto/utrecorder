package dev.klepto.utrecorder.server.cache

import dev.klepto.utrecorder.server.CleanupService
import dev.klepto.utrecorder.server.DemoStore
import dev.klepto.utrecorder.server.Recording
import dev.klepto.utrecorder.server.RecordingState
import dev.klepto.utrecorder.server.ServerConfig
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

/**
 * Deletes the local mp4 of a recording not accessed for [ServerConfig.cacheTtl].
 *
 * A pass, run by [pass] at startup and once a day by [loop], evicts every recording with a local mp4 whose
 * [Recording.accessedAt] is older than the TTL:
 * - [RecordingState.ARCHIVED]: the mp4 is deleted; a later request re-caches it from the Archive.
 * - [RecordingState.RECORDED] with the uploader disabled: the mp4 is deleted and the recording becomes
 *   [RecordingState.EXPIRED], because it has no other copy.
 * - [RecordingState.RECORDED] with the uploader enabled: kept until it is archived.
 *
 * @property store The store recordings are read from and eviction is written through.
 * @property cleanup The service the mp4 of an evicted recording is deleted through.
 * @property config The cache directory a recording's mp4 is resolved against, the archive settings and the TTL.
 */
class CacheEvictor(
    private val store: DemoStore,
    private val cleanup: CleanupService,
    private val config: ServerConfig = ServerConfig(),
) {
    /**
     * Evicts every recording whose local mp4 is due, once.
     */
    suspend fun pass() {
        val cutoff = Instant.now().minus(config.cacheTtl.toJavaDuration())
        store
            .load()
            .recordings
            .filter { it.file != null }
            .filter { recording -> recording.accessedAt?.isBefore(cutoff) == true }
            .forEach { evictIfDue(it) }
    }

    /**
     * Runs [pass], then every day again, until cancelled.
     */
    suspend fun loop() {
        while (true) {
            pass()
            delay(RETRY_INTERVAL)
        }
    }

    private suspend fun evictIfDue(recording: Recording) {
        when (recording.state) {
            RecordingState.ARCHIVED -> evict(recording)
            RecordingState.RECORDED -> if (!config.archive.enabled) evict(recording, expire = true)
            else -> Unit
        }
    }

    private suspend fun evict(
        recording: Recording,
        expire: Boolean = false,
    ) {
        val file = requireNotNull(recording.file)
        cleanup.delete(config.cache.resolve(file))
        store.update(
            recording.copy(
                file = null,
                state = if (expire) RecordingState.EXPIRED else recording.state,
            ),
        )
    }

    companion object {
        private val RETRY_INTERVAL = 1.days
    }
}
