package dev.klepto.utrecorder.server.cache

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Disk usage of the mp4 files in `server.recordings`, as one [CacheService.evict] pass left them.
 *
 * @property bytes The total size of the mp4 files in bytes.
 * @property files The number of mp4 files.
 * @property measuredAt The time the pass ended.
 */
@Serializable
data class CacheUsage(
    val bytes: Long,
    val files: Int,
    val measuredAt: Instant,
)
