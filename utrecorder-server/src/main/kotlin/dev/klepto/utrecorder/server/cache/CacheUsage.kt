package dev.klepto.utrecorder.server.cache

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Disk usage of the mp4 files in `server.recordings`, as one [CacheService.evict] pass left them.
 *
 * @property bytes The total size of the mp4 files in bytes.
 * @property files The number of mp4 files.
 * @property measuredAt The time the pass ended.
 * @property usableBytes The usable bytes of the filesystem holding `server.recordings` that the pass left: the usable
 * bytes read at its start plus the bytes it deleted after the read, or `null` if the read failed.
 */
@Serializable
data class CacheUsage(
    val bytes: Long,
    val files: Int,
    val measuredAt: Instant,
    val usableBytes: Long?,
)
