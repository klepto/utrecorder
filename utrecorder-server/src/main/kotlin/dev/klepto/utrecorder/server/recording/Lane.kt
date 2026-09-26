package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.io.digest
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.core.RecorderProgress
import dev.klepto.utrecorder.server.CleanupService
import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.DemoStore
import dev.klepto.utrecorder.server.Recording
import dev.klepto.utrecorder.server.RecordingLane
import dev.klepto.utrecorder.server.RecordingState
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.archive.InternetArchive
import dev.klepto.utrecorder.ut99.UtPackageException
import kotlinx.coroutines.CancellationException
import java.nio.file.Path
import java.time.Instant

/**
 * One recording worker: takes the recordings of [lane] from [queue], one at a time, and records them.
 *
 * A recording the Archive already lists becomes [RecordingState.ARCHIVED] without being recorded. Any other is recorded
 * through [record], its mp4 is moved into [ServerConfig.cache] as `<recording id>.mp4`, the recording is written
 * [RecordingState.RECORDED], the workspace the mp4 was recorded in is deleted through [cleanup], and the recording is
 * passed to [upload].
 *
 * A failed attempt increments [Recording.attempts] and puts the recording back at the end of [queue] as
 * [RecordingState.PENDING], until [ServerConfig.maxAttempts] attempts have failed. Then, or at once for a
 * [UtPackageException] or a demo that did not parse, the recording becomes [RecordingState.FAILED]. A failed attempt
 * keeps its workspace.
 *
 * @property lane The lane whose recordings this worker takes.
 * @param queue The queue recordings are taken from and put back on.
 * @param store The store every change of a recording is written through.
 * @param cleanup The service the workspace of a recorded mp4 is deleted through.
 * @param archive The client the Archive's metadata is checked through.
 * @param upload Receives each recorded recording with its demo, such as the uploader's `send`.
 * @param config The demo URL, the cache directory, the attempt limit and the Archive settings.
 * @param record Returns the mp4 recorded from the demo at the given URL, reporting progress to the given listener. The
 * mp4's parent directory is the workspace of the recording.
 */
class Lane(
    val lane: RecordingLane,
    private val queue: RecordingQueue,
    private val store: DemoStore,
    private val cleanup: CleanupService,
    private val archive: InternetArchive,
    private val upload: suspend (Demo, Recording) -> Unit,
    private val config: ServerConfig = ServerConfig(),
    private val record: suspend (url: String, onProgress: (RecorderProgress) -> Unit) -> Path,
) {
    /**
     * The progress of the recording this lane records, or `null` while it records none.
     */
    @Volatile
    var progress: RecorderProgress? = null
        private set

    /**
     * Records the recordings of [lane] one after another, until cancelled.
     */
    suspend fun run(): Nothing {
        while (true) {
            recordNext()
        }
    }

    /**
     * Takes the oldest recording of [lane] from the queue, suspending until one is available, and records it.
     */
    suspend fun recordNext() {
        val (demo, recording) = queue.take(lane)
        if (demo.map == null) {
            fail(demo, recording, "Demo ${demo.sourceId} does not parse", retry = false)
            return
        }
        try {
            if (isArchived(demo, recording)) return
            recordDemo(demo, recording)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UtPackageException) {
            log.warn(failure) { "Recording ${recording.id} of demo ${demo.sourceId} failed" }
            fail(demo, recording, failure.message, retry = false)
        } catch (failure: Exception) {
            log.warn(failure) { "Recording ${recording.id} of demo ${demo.sourceId} failed" }
            fail(demo, recording, failure.message, retry = true)
        } finally {
            progress = null
        }
    }

    private suspend fun isArchived(
        demo: Demo,
        recording: Recording,
    ): Boolean {
        if (!config.archive.enabled) return false
        val identifier = archive.identifier(demo.sourceId)
        if (!archive.lists(identifier, fileName(recording))) return false
        store.update(
            recording.copy(
                state = RecordingState.ARCHIVED,
                archiveId = identifier,
                collection = config.archive.collection,
                archivedAt = Instant.now(),
            ),
        )
        return true
    }

    private suspend fun recordDemo(
        demo: Demo,
        pending: Recording,
    ) {
        val recording = pending.copy(state = RecordingState.RECORDING)
        store.update(recording)
        val mp4 = record("${config.demoUrl}/${demo.sourceId}") { progress = it }
        val file = fileName(recording)
        val cached = mp4.move(config.cache[file])
        val md5 = cached.digest("MD5")
        val now = Instant.now()
        val recorded =
            recording.copy(
                state = RecordingState.RECORDED,
                file = file,
                md5 = md5,
                recordedAt = now,
                accessedAt = now,
            )
        store.update(recorded)
        cleanup.delete(mp4.parent)
        upload(demo, recorded)
    }

    private suspend fun fail(
        demo: Demo,
        recording: Recording,
        error: String?,
        retry: Boolean,
    ) {
        val attempts = recording.attempts + 1
        val state = if (retry && attempts < config.maxAttempts) RecordingState.PENDING else RecordingState.FAILED
        val failed = recording.copy(state = state, attempts = attempts, error = error)
        store.update(failed)
        if (state == RecordingState.PENDING) queue.enqueue(demo to failed)
    }

    private fun fileName(recording: Recording): String = "${recording.id}.mp4"

    companion object {
        private val log = logger<Lane>()
    }
}
