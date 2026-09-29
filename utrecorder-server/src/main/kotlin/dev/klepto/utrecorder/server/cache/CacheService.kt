package dev.klepto.utrecorder.server.cache

import dev.klepto.utrecorder.common.http.Downloader
import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
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
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * [Service] that keeps in [ServerConfig.recordings] only the mp4 files worth holding locally.
 *
 * On start, and then once a day, [evict] deletes the mp4 of every [Archived] recording not viewed within
 * [ServerConfig.cacheTtl], and every mp4 that has no recording in [repository].
 *
 * [view] counts one view of a recording. If the recording is [Archived] and its mp4 is not in
 * [ServerConfig.recordings], the mp4 is downloaded again from [uploader] in the background, one recording at a time.
 *
 * A delete or a download that fails is logged, and tried again on the next pass or the next view.
 *
 * @property repository The recordings whose mp4 files are kept.
 * @property uploader The remote storage an evicted mp4 is downloaded from again.
 * @property config The recordings directory, the cache TTL and the directories a download runs in.
 * @property clock The source of the time of a view and of the age of a recording.
 */
class CacheService(
    private val repository: RecordingRepository,
    private val uploader: MediaUploader,
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
     * mp4 is not in [ServerConfig.recordings], queues a download of the mp4, unless one is queued already. If there is
     * no recording, returns `null` instead.
     */
    suspend fun view(capId: String): Recording? {
        repository.update(capId) { it.copy(views = it.views + 1, lastView = clock.now()) }
        return repository[capId]?.also { queueDownload(it) }
    }

    /**
     * Returns the URL the mp4 of the recording [capId] downloads from [uploader].
     */
    fun downloadUrl(capId: String): String = uploader.downloadUrl(capId, mp4(capId).name)

    /**
     * Deletes from [ServerConfig.recordings] the mp4 of every [Archived] recording last viewed more than
     * [ServerConfig.cacheTtl] ago, and every mp4 that has no recording in [repository]. A recording never viewed is
     * measured from the time it was set to [Archived]. The recordings stay in [repository].
     */
    suspend fun evict() {
        val recordings = repository.all().associateBy { it.demo.capId }
        mp4s()
            .filter { isExpired(recordings[it.nameWithoutExtension]) }
            .forEach { delete(it) }
    }

    /**
     * Runs [evict] once a day.
     */
    private suspend fun evictLoop() {
        while (isRunning()) {
            evict()
            delay(1.days)
        }
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
     * Queues a download of the mp4 of [recording] if it is [Archived], its mp4 is missing, and no download of it is
     * queued already.
     */
    private suspend fun queueDownload(recording: Recording) {
        val capId = recording.demo.capId
        if (recording.status != Archived || exists(mp4(capId))) return
        if (downloading.add(capId)) downloads.send(recording)
    }

    /**
     * Downloads the mp4 of [recording] from [uploader] into [ServerConfig.recordings], checked against the checksum of
     * the recording. If the download fails, logs it.
     */
    private suspend fun download(recording: Recording) {
        val capId = recording.demo.capId
        val mp4 = mp4(capId)
        val url = downloadUrl(capId)
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
     * Deletes [file]. If the delete fails, logs it.
     */
    private suspend fun delete(file: Path) {
        file
            .deleteCatching()
            .onSuccess { log.info { "Evicted: $file" } }
            .onFailure { log.warn(it) { "Could not evict $file" } }
    }

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
     * Returns the file that the mp4 of the recording [capId] is stored at.
     */
    private fun mp4(capId: String): Path = config.recordings["$capId.mp4"]

    private suspend fun exists(file: Path): Boolean = withContext(Dispatchers.IO) { file.exists() }

    private companion object {
        val log = logger<CacheService>()
    }
}
