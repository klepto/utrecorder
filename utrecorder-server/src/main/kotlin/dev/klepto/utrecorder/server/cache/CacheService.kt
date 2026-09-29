package dev.klepto.utrecorder.server.cache

import dev.klepto.utrecorder.common.http.Downloader
import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.fileExists
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Evicted
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.upload.MediaUploader
import kotlinx.coroutines.Dispatchers
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

/**
 * [Service] that keeps in [ServerConfig.recordings] only the mp4 files worth holding locally.
 *
 * On start, and then once a minute, [evict] deletes the mp4 of every [Archived] recording not viewed within
 * [ServerConfig.cacheTtl], and every mp4 that has no recording in [repository]. If the mp4 files still take more than
 * [ServerConfig.cacheMaxBytes], it deletes more of them until they take at most
 * [ServerConfig.cacheTargetBytes].
 *
 * [view] counts one view of a recording. If the recording is [Archived] and its mp4 is not in
 * [ServerConfig.recordings], the mp4 is downloaded again from [uploader] in the background, one recording at a time. If
 * the recording is [Evicted], it is set to [Pending], so it is recorded again.
 *
 * A delete or a download that fails is logged, and tried again on the next pass or the next view.
 *
 * @property repository The recordings whose mp4 files are kept.
 * @property uploader The remote storage an evicted mp4 is downloaded from again, or `null` if nothing is uploaded.
 * @property config The recordings directory, the cache TTL, the size limits and the directories a download runs in.
 * @property clock The source of the time of a view and of the age of a recording.
 */
class CacheService(
    private val repository: RecordingRepository,
    private val uploader: MediaUploader?,
    private val config: ServerConfig = ServerConfig(),
    private val clock: Clock = Clock.System,
) : Service {
    private val downloads = Channel<Recording>(Channel.UNLIMITED)
    private val downloading: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun start() {
        coroutineScope {
            launch { downloadLoop() }
            evictLoop()
        }
    }

    /**
     * Returns the recording stored under [capId], after counting one view of it. If the recording is [Archived] and its
     * mp4 is not in [ServerConfig.recordings], queues a download of the mp4, unless one is queued already. If the
     * recording is [Evicted], sets it to [Pending]. If there is no recording, returns `null` instead.
     */
    suspend fun view(capId: String): Recording? {
        repository.update(capId) { viewed(it) }
        return repository[capId]?.also { queueDownload(it) }
    }

    /**
     * Returns the URL the mp4 of the recording [capId] downloads from [uploader]. If there is no [uploader], returns
     * `null` instead.
     */
    fun downloadUrl(capId: String): String? = uploader?.downloadUrl(capId, config.mp4(capId).name)

    /**
     * Deletes from [ServerConfig.recordings] the mp4 of every [Archived] recording last viewed more than
     * [ServerConfig.cacheTtl] ago, and every mp4 that has no recording in [repository]. A recording never viewed is
     * measured from the time it was set to [Archived]. The recordings stay in [repository].
     *
     * If the mp4 files left take more than [ServerConfig.cacheMaxBytes], deletes more of them until they take
     * at most [ServerConfig.cacheTargetBytes]: first those of [Archived] recordings, then those of [Recorded]
     * recordings, each set to [Evicted]. Within each, the least recently viewed goes first, and a recording never
     * viewed is measured from the time its status last changed. The mp4 of any other recording is never deleted.
     */
    suspend fun evict() {
        val recordings = repository.all().associateBy { it.demo.capId }
        mp4s()
            .filter { isExpired(recordings[it.nameWithoutExtension]) }
            .forEach { delete(it) }
        trim(recordings.values)
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
     * Returns [recording] with one more view, viewed now. An [Evicted] recording is returned as [Pending].
     */
    private fun viewed(recording: Recording): Recording =
        recording.copy(
            views = recording.views + 1,
            lastView = clock.now(),
            status = if (recording.status == Evicted) Pending else recording.status,
        )

    /**
     * Deletes the mp4 files of [recordings] in trim order, if they take more than [ServerConfig.cacheMaxBytes],
     * until they take at most [ServerConfig.cacheTargetBytes]. Sets each [Recorded] recording whose mp4 is
     * deleted to [Evicted].
     */
    private suspend fun trim(recordings: Collection<Recording>) {
        val sizes = mp4Sizes()
        var total = sizes.values.sum()
        if (total <= config.cacheMaxBytes) return
        val candidates =
            recordings
                .filter { it.status in TRIM_ORDER && it.demo.capId in sizes }
                .sortedWith(TRIM_COMPARATOR)
        for (recording in candidates) {
            if (total <= config.cacheTargetBytes) break
            val capId = recording.demo.capId
            if (!delete(config.mp4(capId))) continue
            total -= sizes.getValue(capId)
            if (recording.status == Recorded) evictRecorded(capId)
        }
        log.info { "Trimmed ${config.recordings} to $total bytes" }
    }

    /**
     * Sets the recording [capId] to [Evicted], unless it left [Recorded] meanwhile.
     */
    private suspend fun evictRecorded(capId: String) {
        repository.update(capId) { if (it.status == Recorded) it.copy(status = Evicted) else it }
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
     * Queues a download of the mp4 of [recording] if there is an [uploader], [recording] is [Archived], its mp4 is
     * missing, and no download of it is queued already.
     */
    private suspend fun queueDownload(recording: Recording) {
        val capId = recording.demo.capId
        if (uploader == null || recording.status != Archived || config.mp4(capId).fileExists()) return
        if (downloading.add(capId)) downloads.send(recording)
    }

    /**
     * Downloads the mp4 of [recording] from [uploader] into [ServerConfig.recordings], checked against the checksum of
     * the recording. If the download fails, logs it.
     */
    private suspend fun download(recording: Recording) {
        val capId = recording.demo.capId
        val mp4 = config.mp4(capId)
        val url = downloadUrl(capId) ?: return
        try {
            suspendCatching { Downloader(url, recording.md5, directories = config.directories).download(mp4) }
                .onSuccess { log.info { "Downloaded again: ${recording.demo} ($url)" } }
                .onFailure { log.warn(it) { "Could not download recording $capId from $url" } }
        } finally {
            downloading -= capId
        }
    }

    /**
     * Returns `true` if the mp4 of [recording] may be deleted: [recording] is `null`, or it is [Archived] and was last
     * viewed, or else set to [Archived], more than [ServerConfig.cacheTtl] ago.
     */
    private fun isExpired(recording: Recording?): Boolean =
        when {
            recording == null -> true
            recording.status != Archived -> false
            else -> clock.now() - (recording.lastView ?: recording.lastUpdate) > config.cacheTtl
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

    private companion object {
        val TRIM_ORDER = listOf(Archived, Recorded)

        val TRIM_COMPARATOR: Comparator<Recording> =
            compareBy<Recording> { TRIM_ORDER.indexOf(it.status) }
                .thenBy { it.lastView ?: it.lastUpdate }

        val log = logger<CacheService>()
    }
}
