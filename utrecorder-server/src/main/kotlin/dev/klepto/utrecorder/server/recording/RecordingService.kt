package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * [Service] that records the demos of [queue] into [ServerConfig.recordings], newest first.
 *
 * A demo whose cap time is at most [RecordingConfig.longThreshold] is recorded on one of [primaryRecorders], a longer
 * one on one of [secondaryRecorders]. Each recorder takes its demos from [queue] on its own, so a busy secondary
 * recorder never holds up a short demo. If [secondaryRecorders] is empty, [primaryRecorders] record the long demos too.
 * Each recorder records one demo at a time, and waits [RecordingConfig.cooldown] after each attempt before it records
 * again. The cooldown after the last attempt starts once the recording is stored. A failed demo is recorded again on
 * the same recorder, up to [RecordingConfig.maxAttempts] attempts. A demo that names a package no source holds is
 * given up on at once. A demo whose mp4 is already stored is set to [Recorded] without recording it. Each recording is
 * tracked in [jobs] from its first attempt until its last one ends, and its outcome is set on [queue].
 *
 * @property queue The demos to record.
 * @property primaryRecorders The recorders of the demos up to the long threshold.
 * @property secondaryRecorders The recorders of the demos over the long threshold. May be empty.
 * @property config The recordings directory, the long threshold, the attempts and the cooldown.
 * @property jobs The list the running recordings are tracked in.
 * @throws IllegalArgumentException If [primaryRecorders] is empty.
 */
class RecordingService(
    private val queue: RecordingQueue,
    private val primaryRecorders: List<RecorderProvider> = listOf(DefaultRecorderProvider()),
    private val secondaryRecorders: List<RecorderProvider> = emptyList(),
    private val config: ServerConfig = ServerConfig(),
    private val jobs: RecordingJobs = RecordingJobs(),
) : Service {
    init {
        require(primaryRecorders.isNotEmpty()) { "Primary recorders must not be empty" }
    }

    override suspend fun start(): Unit =
        coroutineScope {
            primaryRecorders.forEach { recorder ->
                launch { recorder.recordQueued { secondaryRecorders.isEmpty() || it.isShort() } }
            }
            secondaryRecorders.forEach { recorder ->
                launch { recorder.recordQueued { !it.isShort() } }
            }
            awaitCancellation()
        }

    /**
     * Records the demos of [queue] that [accepts] returns `true` for on this recorder, one at a time.
     */
    private suspend fun RecorderProvider.recordQueued(accepts: (Demo) -> Boolean): Nothing {
        while (true) {
            val demo = queue.take(accepts)
            if (isRecorded(demo)) {
                queue.finish(demo.capId, Recorded)
                continue
            }
            processRecording(demo, recordTracked(demo))
            delay(config.recording.cooldown)
        }
    }

    /**
     * Returns `true` if the cap time of this demo is at most [RecordingConfig.longThreshold].
     */
    private fun Demo.isShort(): Boolean = capTime <= config.recording.longThreshold

    /**
     * Stores the mp4 of [result] if it is done, and logs it otherwise. Sets the outcome for [demo] on [queue].
     */
    private suspend fun processRecording(
        demo: Demo,
        result: RecorderResult,
    ) {
        val capId = demo.capId
        when (result) {
            is RecorderResult.Done -> {
                suspendCatching { store(demo, result.mp4) }
                    .onSuccess { queue.finish(capId, result.status) }
                    .onFailure {
                        log.error(it) { "Could not store recording $capId" }
                        queue.finish(capId, Failed, it.toString())
                    }
            }

            is RecorderResult.Failed -> {
                log.error(result.exception) { "Giving up on recording $capId after ${config.recording.maxAttempts} attempts" }
                queue.finish(capId, result.status, result.exception.toString())
            }

            is RecorderResult.Unresolvable -> {
                log.error(result.exception) { "Giving up on recording $capId, couldn't resolve required packages" }
                queue.finish(capId, result.status, result.exception.toString())
            }
        }
    }

    /**
     * Returns the result of [recordRetrying], tracked in [jobs]. Logs [demo] before the recording starts, and again
     * with the time the recording took once it ends.
     */
    private suspend fun RecorderProvider.recordTracked(demo: Demo): RecorderResult {
        log.info { "Recording: $demo" }
        val (result, elapsed) = measureTimedValue { jobs.track(demo) { recordRetrying(demo, it) } }
        log.info { "Finished recording: $demo in ${elapsed.inWholeSeconds.seconds}" }
        return result
    }

    /**
     * Returns the result of the last attempt at recording [demo] on this recorder. Records again after each
     * [RecorderResult.Failed], up to [RecordingConfig.maxAttempts] attempts, once the cooldown that follows the failed
     * attempt has passed. Each attempt and its progress are reported to [tracker].
     */
    private suspend fun RecorderProvider.recordRetrying(
        demo: Demo,
        tracker: RecordingJobs.Tracker,
    ): RecorderResult {
        var attempt = 1
        while (true) {
            tracker.startAttempt(attempt)
            val result = record(demo, tracker::report)
            if (result !is RecorderResult.Failed || attempt >= config.recording.maxAttempts) return result
            log.warn(result.exception) { "Recording ${demo.capId} failed, attempt $attempt of ${config.recording.maxAttempts}" }
            delay(config.recording.cooldown)
            attempt++
        }
    }

    /**
     * Moves [recordingPath] to the recorded file of [demo], then deletes the workspace that held it.
     */
    private suspend fun store(
        demo: Demo,
        recordingPath: Path,
    ) {
        recordingPath.move(recordingDestinationPath(demo))
        recordingPath.parent
            .deleteCatching()
            .onFailure { log.warn(it) { "Could not delete the workspace ${recordingPath.parent}" } }
    }

    /**
     * Returns the file that the mp4 of [demo] is stored at.
     */
    private fun recordingDestinationPath(demo: Demo): Path = config.recordings["${demo.capId}.mp4"]

    /**
     * Returns `true` if the mp4 of [demo] is stored.
     */
    private suspend fun isRecorded(demo: Demo): Boolean = withContext(Dispatchers.IO) { recordingDestinationPath(demo).exists() }

    private companion object {
        val log = logger<RecordingService>()
    }
}
