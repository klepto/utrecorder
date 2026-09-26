package dev.klepto.utrecorder.server.archive

import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.archive.Uploader.Companion.MAX_BACKOFF
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Uploads recorded mp4 files to the Internet Archive, one at a time, in the order they are received.
 *
 * Receives recordings on a channel with [send], and processes them in [run] until [close] ends the channel. A failed
 * upload or metadata check never fails permanently: it is logged and retried with exponential backoff capped at
 * [MAX_BACKOFF], so one failing recording never stops the recordings queued behind it. A
 * recording is written [RecordingState.ARCHIVED] through [recordings] only once the Archive's metadata confirms the file
 * with a matching checksum, because a successful `PUT` only means the file is staged.
 *
 * With [ServerConfig.archive] disabled, [send] and [run] are no-ops: nothing is ever queued, and [archive] is never
 * called.
 *
 * @property archive The Internet Archive client the uploads and metadata checks run through.
 * @property recordings The collection a recording is written [RecordingState.ARCHIVED] through.
 * @property config The cache directory recordings are resolved against, and the Archive settings.
 */
class Uploader(
    private val archive: InternetArchive,
    private val recordings: DocumentStore.Collection<Recording>,
    private val config: ServerConfig = ServerConfig(),
) {
    private val channel = Channel<Pair<Demo, Recording>>(Channel.UNLIMITED)

    /**
     * Whether the channel holds no recording waiting to be uploaded.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isEmpty: Boolean
        get() = channel.isEmpty

    /**
     * Queues [recording] of [demo] for upload. Does nothing when the Archive is disabled.
     */
    suspend fun send(
        demo: Demo,
        recording: Recording,
    ) {
        if (!config.archive.enabled) return
        channel.send(demo to recording)
    }

    /**
     * Closes the channel. A [run] loop returns once every already-queued recording is processed.
     */
    fun close() {
        channel.close()
    }

    /**
     * Uploads every recording received on the channel until it is closed and drained. Returns immediately when the
     * Archive is disabled.
     */
    suspend fun run() {
        if (!config.archive.enabled) return
        for ((demo, recording) in channel) {
            archiveRecording(demo, recording)
        }
    }

    private suspend fun archiveRecording(
        demo: Demo,
        recording: Recording,
    ) {
        val identifier = archive.identifier(demo.id)
        val file = requireNotNull(recording.file) { "Recording of demo ${demo.id} has no file to archive" }
        val md5 = requireNotNull(recording.md5) { "Recording of demo ${demo.id} has no md5 to archive" }
        val path = config.cache.resolve(file)

        retryingOnFailure { archive.upload(identifier, path, demo) }
        retryingUntilTrue { archive.isArchived(identifier, file, md5) }

        val archived =
            recording.copy(
                state = RecordingState.ARCHIVED,
                archiveId = identifier,
                collection = config.archive.collection,
                archivedAt = Instant.now(),
            )
        recordings.put(archived)
    }

    private suspend fun retryingOnFailure(action: suspend () -> Unit) {
        var backoff = INITIAL_BACKOFF
        while (true) {
            try {
                action()
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.warn(failure) { "Archive upload failed, retrying in $backoff" }
                delay(backoff)
                backoff = nextBackoff(backoff)
            }
        }
    }

    private suspend fun retryingUntilTrue(check: suspend () -> Boolean) {
        var backoff = INITIAL_BACKOFF
        while (true) {
            try {
                if (check()) return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.warn(failure) { "Archive metadata check failed, retrying in $backoff" }
            }
            delay(backoff)
            backoff = nextBackoff(backoff)
        }
    }

    private fun nextBackoff(current: Duration): Duration = (current * 2).coerceAtMost(MAX_BACKOFF)

    companion object {
        private val log = logger<Uploader>()

        /**
         * The delay before the first retry of a failed upload or a metadata check that does not list the file yet.
         */
        private val INITIAL_BACKOFF = 40.minutes

        /**
         * The delay a retry never exceeds, however many attempts failed.
         */
        private val MAX_BACKOFF = 60.minutes
    }
}
