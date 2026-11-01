package dev.klepto.utrecorder.server.cache

import dev.klepto.utrecorder.common.http.Downloader
import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.fileExists
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.DiskSpace
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.upload.MediaUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * [Service] that keeps in [ServerConfig.recordings] only the mp4 files worth holding locally.
 *
 * On start, and then once a minute, [evict] deletes the mp4 of every [Archived] recording neither recorded nor viewed
 * within [ServerConfig.cacheTtl], and of every [Outdated] recording whose [Recording.lastUpdate] is older than
 * [ServerConfig.cacheTtl], and every mp4 that has no recording in [repository]. If the filesystem holding
 * [ServerConfig.recordings] then has less than [ServerConfig.cacheMinFreeBytes] usable, it deletes more of them until
 * it has [ServerConfig.cacheTargetFreeBytes]: first [Archived], then [Outdated]. The mp4 of a `Recorded`, `Uploaded` or
 * `Recording` recording is never deleted.
 *
 * [view] counts one view of a recording. The views are counted in memory, and [flushViews] writes them to [repository]:
 * once every [ServerConfig.viewFlushInterval], and once more when the service stops. If the recording is [Archived]
 * and its mp4 is not in [ServerConfig.recordings], the mp4 is downloaded again from [uploader] in the background, one
 * recording at a time.
 *
 * Each pass also sets the [Recording.size] of every recording without one whose mp4 is in [ServerConfig.recordings],
 * and keeps the [CacheUsage] of the mp4 files it leaves in [usage].
 *
 * A delete or a download that fails is logged, and tried again on the next pass or the next view.
 *
 * @property repository The recordings whose mp4 files are kept.
 * @property uploader The remote storage a missing mp4 is downloaded from again, or `null` if nothing is uploaded.
 * @property config The recordings directory, the cache TTL, the free space limits and the directories a download runs
 * in.
 * @property clock The source of the time of a view and of the age of a recording.
 * @property diskSpace The source of the usable bytes of the filesystem holding [ServerConfig.recordings].
 */
class CacheService(
    private val repository: RecordingRepository,
    private val uploader: MediaUploader?,
    private val config: ServerConfig = ServerConfig(),
    private val clock: Clock = Clock.System,
    private val diskSpace: DiskSpace = DiskSpace.SYSTEM,
) : Service {
    private val downloads = Channel<Recording>(Channel.UNLIMITED)
    private val downloading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val views = ConcurrentHashMap<String, View>()

    /**
     * Usage of the mp4 files in [ServerConfig.recordings] that the last [evict] pass left, or `null` until the first
     * pass ends. Thumbnails are not counted.
     */
    @Volatile
    var usage: CacheUsage? = null
        private set

    override suspend fun start() {
        try {
            coroutineScope {
                launch { downloadLoop() }
                launch { flushLoop() }
                evictLoop()
            }
        } finally {
            withContext(NonCancellable) { flushViews() }
        }
    }

    /**
     * Returns the recording stored under [capId], with every view counted so far, after counting one more view of it.
     * If the recording is [Archived] and its mp4 is not in [ServerConfig.recordings], queues a download of the mp4,
     * unless one is queued already. If there is no recording, returns `null` instead.
     *
     * If [counted] is `false`, counts no view.
     */
    suspend fun view(
        capId: String,
        counted: Boolean = true,
    ): Recording? {
        val recording = repository[capId] ?: return null
        if (counted) views.merge(capId, View(1, clock.now()), View::plus)
        return withViews(recording).also { queueDownload(it) }
    }

    /**
     * Writes every view counted in memory to [repository], as one transaction.
     */
    suspend fun flushViews() {
        val flushed =
            views.keys
                .toList()
                .mapNotNull { capId -> views.remove(capId)?.let { capId to it } }
                .toMap()
        if (flushed.isEmpty()) return
        repository.updateAll { recording -> flushed[recording.demo.capId]?.let { viewed(recording, it) } ?: recording }
    }

    /**
     * Returns the URL the mp4 of [recording] downloads from [uploader]: the file `<capId>.mp4` of the item
     * [Recording.itemId]. If there is no [uploader], or [recording] has no [Recording.itemId], returns `null` instead.
     */
    fun downloadUrl(recording: Recording): String? {
        val itemId = recording.itemId ?: return null
        return uploader?.downloadUrl(itemId, config.mp4(recording.demo.capId).name)
    }

    /**
     * Deletes from [ServerConfig.recordings] the mp4 of every [Archived] recording recorded more than
     * [ServerConfig.cacheTtl] ago and not viewed since then, and of every [Outdated] recording whose
     * [Recording.lastUpdate] is older than [ServerConfig.cacheTtl]. An [Archived] recording without
     * [Recording.recordedAt] is measured from [Recording.lastUpdate] instead. Also deletes every mp4 that has no
     * recording in [repository]. The recordings stay in [repository].
     *
     * Then reads the usable bytes of the filesystem holding [ServerConfig.recordings] once. If they are less than
     * [ServerConfig.cacheMinFreeBytes], deletes more mp4 files until the usable bytes read plus the bytes deleted reach
     * [ServerConfig.cacheTargetFreeBytes]: first those of [Archived] recordings, then, only once no [Archived] mp4 is
     * left, those of [Outdated] recordings. Within [Archived], the one recorded or viewed longest ago goes first; within
     * [Outdated], the oldest [Recording.lastUpdate] goes first. The mp4 of any other recording is never deleted. When
     * only such mp4 files are left below [ServerConfig.cacheTargetFreeBytes], logs a warning and stops. If the read
     * fails, logs it and deletes nothing more.
     *
     * Before deleting anything, sets the [Recording.size] of every recording without one whose mp4 is in
     * [ServerConfig.recordings], as one transaction that changes no status. If no recording needs a size, writes nothing.
     * Once done, sets [usage] to the mp4 files left and the usable bytes the pass left, measured at the time [clock]
     * reports.
     */
    suspend fun evict() {
        val recordings =
            repository
                .all()
                .map { withViews(it) }
                .associateBy { it.demo.capId }
        val sizes = mp4Sizes().toMutableMap()
        storeSizes(recordings.values, sizes)
        sizes.keys
            .filter { isExpired(recordings[it]) }
            .filter { delete(config.mp4(it)) }
            .forEach { sizes -= it }
        val usable = usableBytes()?.let { it + trim(recordings.values, sizes, it) }
        usage = CacheUsage(sizes.values.sum(), sizes.size, clock.now(), usable)
    }

    /**
     * Returns the usable bytes of the filesystem holding [ServerConfig.recordings]. If the read fails, logs it and
     * returns `null` instead.
     */
    private suspend fun usableBytes(): Long? =
        suspendCatching { diskSpace.usableBytes(config.recordings) }
            .onFailure { log.warn(it) { "Could not read the usable space of ${config.recordings}" } }
            .getOrNull()

    /**
     * Sets the [Recording.size] of every recording of [repository] without one to its size in [sizes], keyed by cap id,
     * as one transaction. If none of [recordings] lacks a size that [sizes] holds, writes nothing.
     */
    private suspend fun storeSizes(
        recordings: Collection<Recording>,
        sizes: Map<String, Long>,
    ) {
        if (recordings.none { it.size == null && it.demo.capId in sizes }) return
        repository.updateAll { recording ->
            when (recording.size) {
                null -> sizes[recording.demo.capId]?.let { recording.copy(size = it) } ?: recording
                else -> recording
            }
        }
    }

    /**
     * Runs [evict] once a minute.
     */
    private suspend fun evictLoop() {
        while (isRunning()) {
            evict()
            delay(1.minutes)
        }
    }

    /**
     * Runs [flushViews] once every [ServerConfig.viewFlushInterval].
     */
    private suspend fun flushLoop() {
        while (isRunning()) {
            delay(config.viewFlushInterval)
            flushViews()
        }
    }

    /**
     * Returns [recording] with the views counted in memory and not flushed yet.
     */
    private fun withViews(recording: Recording): Recording = views[recording.demo.capId]?.let { viewed(recording, it) } ?: recording

    /**
     * Deletes the mp4 files of [recordings] in trim order, if [usable] is less than [ServerConfig.cacheMinFreeBytes],
     * until [usable] plus the bytes deleted reach [ServerConfig.cacheTargetFreeBytes]. Removes each deleted mp4 from
     * [sizes], and returns the bytes deleted. Logs a warning if only mp4 files that are never trimmed are left before
     * the target is reached.
     */
    private suspend fun trim(
        recordings: Collection<Recording>,
        sizes: MutableMap<String, Long>,
        usable: Long,
    ): Long {
        if (usable >= config.cacheMinFreeBytes) return 0
        var freed = 0L
        val candidates =
            recordings
                .filter { it.status in TRIM_ORDER && it.demo.capId in sizes }
                .sortedWith(TRIM_COMPARATOR)
        for (recording in candidates) {
            if (usable + freed >= config.cacheTargetFreeBytes) break
            val capId = recording.demo.capId
            if (!delete(config.mp4(capId))) continue
            freed += sizes.remove(capId) ?: 0
        }
        val left = usable + freed
        if (left < config.cacheTargetFreeBytes) {
            log.warn { "Cannot trim ${config.recordings} past $left usable bytes: only undeletable mp4 files left" }
        } else {
            log.info { "Trimmed ${config.recordings} to $left usable bytes" }
        }
        return freed
    }

    /**
     * Downloads the mp4 of every recording [queueDownload] queues, one at a time.
     */
    private suspend fun downloadLoop() {
        for (recording in downloads) {
            download(recording)
        }
    }

    /**
     * Queues a download of the mp4 of [recording] if it has a [downloadUrl], [recording] is [Archived], its mp4 is
     * missing, and no download of it is queued already.
     */
    private suspend fun queueDownload(recording: Recording) {
        val capId = recording.demo.capId
        if (downloadUrl(recording) == null || recording.status != Archived || config.mp4(capId).fileExists()) return
        if (downloading.add(capId)) downloads.send(recording)
    }

    /**
     * Downloads the mp4 of [recording] from [uploader] into [ServerConfig.recordings], checked against the checksum of
     * the recording. If the download fails, logs it.
     */
    private suspend fun download(recording: Recording) {
        val capId = recording.demo.capId
        val mp4 = config.mp4(capId)
        try {
            val url = downloadUrl(recording) ?: return
            suspendCatching { Downloader(url, recording.md5, directories = config.directories).download(mp4) }
                .onSuccess { log.info { "Downloaded again: ${recording.demo} ($url)" } }
                .onFailure { log.warn(it) { "Could not download recording $capId from $url" } }
        } finally {
            downloading -= capId
        }
    }

    /**
     * Returns `true` if the mp4 of [recording] may be deleted: [recording] is `null`, or it is [Archived] or [Outdated]
     * and its [ageStart] is more than [ServerConfig.cacheTtl] ago.
     */
    private fun isExpired(recording: Recording?): Boolean =
        when {
            recording == null -> true
            recording.status in TRIM_ORDER -> clock.now() - ageStart(recording) > config.cacheTtl
            else -> false
        }

    /**
     * Deletes [file], and returns `true` if it is gone. If the delete fails, logs it and returns `false` instead.
     */
    private suspend fun delete(file: Path): Boolean =
        file
            .deleteCatching()
            .onSuccess { log.info { "Evicted: $file" } }
            .onFailure { log.warn(it) { "Could not evict $file" } }
            .isSuccess

    /**
     * Returns every mp4 in [ServerConfig.recordings]. If the directory does not exist, returns an empty list instead.
     */
    private suspend fun mp4s(): List<Path> =
        withContext(Dispatchers.IO) {
            when {
                config.recordings.exists() -> config.recordings.listDirectoryEntries("*.mp4")
                else -> emptyList()
            }
        }

    /**
     * Returns the size in bytes of every mp4 in [ServerConfig.recordings], keyed by cap id.
     */
    private suspend fun mp4Sizes(): Map<String, Long> =
        withContext(Dispatchers.IO) {
            mp4s().associate { it.nameWithoutExtension to it.fileSize() }
        }

    /**
     * Views of one recording counted in memory.
     *
     * @property count The number of views.
     * @property lastView The time of the last view.
     */
    private data class View(
        val count: Int,
        val lastView: Instant,
    ) {
        operator fun plus(other: View): View = View(count + other.count, maxOf(lastView, other.lastView))
    }

    private companion object {
        val TRIM_ORDER = listOf(Archived, Outdated)

        val TRIM_COMPARATOR: Comparator<Recording> =
            compareBy<Recording> { TRIM_ORDER.indexOf(it.status) }
                .thenBy { ageStart(it) }

        val log = logger<CacheService>()

        /**
         * Returns the time the mp4 of [recording] ages from. For [Outdated], this is [Recording.lastUpdate], ignoring
         * any view. For any other status, it is the later of [Recording.recordedAt] and [Recording.lastView]. If the
         * recording has neither, returns [Recording.lastUpdate] instead.
         */
        fun ageStart(recording: Recording): Instant =
            when (recording.status) {
                Outdated -> recording.lastUpdate
                else -> listOfNotNull(recording.recordedAt, recording.lastView).maxOrNull() ?: recording.lastUpdate
            }

        /**
         * Returns [recording] with the views of [view] added to it.
         */
        fun viewed(
            recording: Recording,
            view: View,
        ): Recording = recording.copy(views = recording.views + view.count, lastView = view.lastView)
    }
}
