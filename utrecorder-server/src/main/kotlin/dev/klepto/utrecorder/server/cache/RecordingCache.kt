package dev.klepto.utrecorder.server.cache

import dev.klepto.utrecorder.common.http.Downloader
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.archive.InternetArchive
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.recording.Recording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.time.Instant

/**
 * Cache directory of recorded mp4 files, backed by the stored recordings.
 *
 * The path of a cached recording is answered by [file], and every access is recorded with [touch]. A recording
 * without a local mp4 is fetched back from the Internet Archive by [startRecache], which queues the download on a
 * channel drained by [run] until [close] ends it, the way [dev.klepto.utrecorder.server.archive.Uploader] drains its
 * own channel. [startRecache] does nothing for a recording already queued or downloading, so a second request never
 * starts a second download of the same recording.
 *
 * @property recordings The collection a touch or a completed re-cache is written through.
 * @property downloadUrl Builds the Archive download URL of a file in an item. Production passes
 *   [InternetArchive.downloadUrl]; a test passes a URL pointing at a local server instead.
 * @property config The cache directory downloads land in, and the directories a download's workspace is opened in.
 */
class RecordingCache(
    private val recordings: DocumentStore.Collection<Recording>,
    private val downloadUrl: (identifier: String, file: String) -> String,
    private val config: ServerConfig = ServerConfig(),
) {
    constructor(
        recordings: DocumentStore.Collection<Recording>,
        archive: InternetArchive,
        config: ServerConfig = ServerConfig(),
    ) : this(recordings, archive::downloadUrl, config)

    private val channel = Channel<Recording>(Channel.UNLIMITED)
    private val recaching = mutableSetOf<String>()
    private val mutex = Mutex()

    /**
     * Returns the absolute path of [recording]'s cached mp4, or `null` if it has none.
     */
    fun file(recording: Recording): Path? = recording.file?.let(config.cache::resolve)

    /**
     * Sets [recording]'s [Recording.accessedAt] to now, in [recordings], and returns the updated recording.
     */
    suspend fun touch(recording: Recording): Recording {
        val touched = recording.copy(accessedAt = Instant.now())
        recordings.put(touched)
        return touched
    }

    /**
     * Queues a re-cache of [recording] from the Archive, unless one is already queued or running for it.
     */
    suspend fun startRecache(recording: Recording) {
        val started = mutex.withLock { recaching.add(recording.demoId) }
        if (!started) return
        channel.send(recording)
    }

    /**
     * Returns the Archive URL [recording]'s mp4 is downloaded from.
     */
    fun downloadUrl(recording: Recording): String {
        val identifier = requireNotNull(recording.archiveId) { "Recording of demo ${recording.demoId} has no archive id" }
        return downloadUrl(identifier, fileName(recording))
    }

    /**
     * Closes the channel. A [run] loop returns once every already-queued re-cache is processed.
     */
    fun close() {
        channel.close()
    }

    /**
     * Downloads every recording queued by [startRecache] until the channel is closed and drained.
     */
    suspend fun run() {
        for (recording in channel) {
            recache(recording)
        }
    }

    private suspend fun recache(recording: Recording) {
        try {
            val file = fileName(recording)
            val url = downloadUrl(recording)
            Downloader(url, recording.md5, directories = config.directories).download(config.cache.resolve(file))
            val cached = recording.copy(file = file, accessedAt = Instant.now())
            recordings.put(cached)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.warn(failure) { "Failed to re-cache the recording of demo ${recording.demoId}" }
        } finally {
            mutex.withLock { recaching.remove(recording.demoId) }
        }
    }

    private fun fileName(recording: Recording): String = "${recording.demoId}.mp4"

    companion object {
        private val log = logger<RecordingCache>()
    }
}
