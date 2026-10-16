package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.fileExists
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.fileSize
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimedValue
import kotlin.time.measureTimedValue

/**
 * [Service] that records the head of [queue] into [ServerConfig.recordings], one demo at a time on each of [recorders].
 *
 * Each of [recorders] takes the head of [queue] on its own, and records it once it claims the recording on
 * [repository]. If another claimed it in between, the recorder takes the head of [queue] again. Each records one demo
 * at a time. It waits [ServerConfig.recordingCooldown] after each attempt before it records again. The cooldown after
 * the last attempt starts once the recording is stored. A failed demo is recorded again on the same recorder, up to
 * [ServerConfig.recordingMaxAttempts] attempts. A demo that names a package no source holds, or that the game reports it
 * cannot play, is given up on at once. A demo whose mp4 is already stored is set to [Recorded] without recording it.
 * Each recording is tracked in [jobs] from its first attempt until its last one ends, and its outcome is set on
 * [repository].
 *
 * @property repository The recordings, which a recorder claims a recording on.
 * @property queue The order the recordings are taken in.
 * @property recorders The recorders that take the demos.
 * @property config The recordings directory, the attempts and the cooldown.
 * @property jobs The list the running recordings are tracked in.
 * @throws IllegalArgumentException If [recorders] is empty.
 */
class RecordingService(
    private val repository: RecordingRepository,
    private val queue: RecordingQueue,
    private val recorders: List<RecorderProvider> = listOf(DefaultRecorderProvider()),
    private val config: ServerConfig = ServerConfig(),
    private val jobs: RecordingJobs = RecordingJobs(),
) : Service {
    init {
        require(recorders.isNotEmpty()) { "Recorders must not be empty" }
    }

    override suspend fun start(): Unit =
        coroutineScope {
            recorders.forEach { launch { it.recordQueued() } }
        }

    /**
     * Records the head of [queue] on this recorder, one at a time, once it claims its recording on [repository].
     */
    private suspend fun RecorderProvider.recordQueued() {
        while (isRunning()) {
            val recording = repository.claim(queue.first().demo.capId) ?: continue
            val demo = recording.demo
            if (config.mp4(demo.capId).fileExists()) {
                repository.finish(demo.capId, Recorded)
                continue
            }
            val (result, processingTime) = recordTracked(recording)
            processRecording(demo, result, processingTime)
            delay(config.recordingCooldown)
        }
    }

    /**
     * Stores the mp4 and the thumbnail of [result] if it is done, and logs it otherwise. Sets the outcome for [demo] on [repository]. A
     * stored mp4 is recorded in [processingTime], with the video of [result].
     */
    private suspend fun processRecording(
        demo: Demo,
        result: RecorderResult,
        processingTime: Duration,
    ) {
        val capId = demo.capId
        when (result) {
            is RecorderResult.Done -> {
                suspendCatching { store(demo, result) }
                    .onSuccess { repository.recorded(capId, processingTime, it, result.video) }
                    .onFailure {
                        log.error(it) { "Could not store recording $capId" }
                        repository.finish(capId, Failed, it.toString())
                    }
            }

            is RecorderResult.Failed -> {
                log.error(result.exception) { "Giving up on recording $capId after ${config.recordingMaxAttempts} attempts" }
                repository.finish(capId, Failed, result.exception.toString())
            }

            is RecorderResult.Unplayable -> {
                log.error(result.exception) { "Giving up on recording $capId, the game cannot play its demo" }
                repository.finish(capId, Failed, result.exception.toString())
            }

            is RecorderResult.Unresolvable -> {
                log.error(result.exception) { "Giving up on recording $capId, couldn't resolve required packages" }
                val missingPackage = MissingPackage(result.exception.packageName, result.exception.guid)
                repository.finish(capId, Unresolvable, result.exception.toString(), missingPackage)
            }
        }
    }

    /**
     * Returns the result of [recordRetrying] on the demo of [recording], tracked in [jobs]. Logs the demo before the
     * recording starts, and again with the time the recording took once it ends.
     */
    private suspend fun RecorderProvider.recordTracked(recording: Recording): TimedValue<RecorderResult> {
        val demo = recording.demo
        log.info { "Recording: $demo" }
        val (result, elapsed) = measureTimedValue { jobs.track(recording) { recordRetrying(demo, it) } }
        log.info { "Finished recording: $demo in ${elapsed.inWholeSeconds.seconds}" }
        return result
    }

    /**
     * Returns the result of the last attempt at recording [demo] on this recorder, with the time that attempt took.
     * Records again after each [RecorderResult.Failed], up to [ServerConfig.recordingMaxAttempts] attempts, once the cooldown
     * that follows the failed attempt has passed. Each attempt and its progress are reported to [tracker].
     *
     * @throws CancellationException If the service stops running before the last attempt.
     */
    private suspend fun RecorderProvider.recordRetrying(
        demo: Demo,
        tracker: RecordingJobs.Tracker,
    ): TimedValue<RecorderResult> {
        var attempt = 1
        while (isRunning()) {
            tracker.startAttempt(attempt)
            val timedResult = measureTimedValue { record(demo, tracker::report) }
            val result = timedResult.value
            if (result !is RecorderResult.Failed || attempt >= config.recordingMaxAttempts) return timedResult
            log.warn(result.exception) { "Recording ${demo.capId} failed, attempt $attempt of ${config.recordingMaxAttempts}" }
            delay(config.recordingCooldown)
            attempt++
        }
        throw CancellationException("Stopped recording ${demo.capId} before its last attempt")
    }

    /**
     * Returns the size in bytes of the mp4 of [result], after moving the mp4 and the thumbnail of [result] to the
     * recorded files of [demo] and deleting the workspace that held them.
     */
    private suspend fun store(
        demo: Demo,
        result: RecorderResult.Done,
    ): Long {
        val mp4 = config.mp4(demo.capId)
        result.thumbnail.move(config.thumbnail(demo.capId))
        result.mp4.move(mp4)
        result.mp4.parent
            .deleteCatching()
            .onFailure { log.warn(it) { "Could not delete the workspace ${result.mp4.parent}" } }
        return withContext(Dispatchers.IO) { mp4.fileSize() }
    }

    private companion object {
        val log = logger<RecordingService>()
    }
}
