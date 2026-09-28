package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * [Service] that records every demo [fetcher] emits into [ServerConfig.recordings].
 *
 * A demo whose cap time is at most [ServerConfig.longThreshold] is recorded on one of [primaryRecorders], a longer one
 * on one of [secondaryRecorders]. Primary and secondary recorders fetch and record apart, so a busy secondary recorder
 * never holds up a short demo. If [secondaryRecorders] is empty, [primaryRecorders] record the long demos too. Each
 * recorder records one demo at a time, and waits [ServerConfig.cooldown] after each attempt before it records again. The
 * cooldown after the last attempt starts once the recording is stored. A failed demo is recorded again on the same recorder, up to
 * [ServerConfig.maxAttempts] attempts. A demo that names a package no source holds is given up on at once. A demo whose
 * mp4 is already stored is skipped. Each recording is tracked in [jobs] from its first attempt until its last one ends.
 *
 * @property fetcher The source of the demos to record.
 * @property primaryRecorders The recorders of the demos up to the long threshold.
 * @property secondaryRecorders The recorders of the demos over the long threshold. May be empty.
 * @property config The recordings directory, the long threshold, the attempts and the cooldown.
 * @property jobs The list the running recordings are tracked in.
 * @throws IllegalArgumentException If [primaryRecorders] is empty.
 */
class RecordingService(
    private val fetcher: DemoFetcher,
    private val primaryRecorders: List<CaptureProvider> = listOf(DefaultCaptureProvider()),
    private val secondaryRecorders: List<CaptureProvider> = emptyList(),
    private val config: ServerConfig = ServerConfig(),
    private val jobs: RecordingJobs = RecordingJobs(),
) : Service {
    init {
        require(primaryRecorders.isNotEmpty()) { "Primary recorders must not be empty" }
    }

    override suspend fun start(): Unit =
        coroutineScope {
            startPrimaryRecorders()
            startSecondaryRecorders()
            awaitCancellation()
        }

    /**
     * Starts recording the short demos on [primaryRecorders] in this scope. If [secondaryRecorders] is empty, starts
     * recording the long demos on [primaryRecorders] too.
     */
    private fun CoroutineScope.startPrimaryRecorders() {
        launch { runRecorders(primaryRecorders) { secondaryRecorders.isEmpty() || it.isShort() } }
    }

    /**
     * Starts recording the long demos on [secondaryRecorders] in this scope. If [secondaryRecorders] is empty, starts
     * nothing.
     */
    private fun CoroutineScope.startSecondaryRecorders() {
        if (secondaryRecorders.isEmpty()) return
        launch { runRecorders(secondaryRecorders) { !it.isShort() } }
    }

    /**
     * Records the demos of [fetcher] on [recorders], in the order the fetcher emits them.
     *
     * Only the demos that [accepts] returns `true` for are recorded, and every demo whose mp4 is stored is skipped. The
     * next demo is fetched only once a recorder takes the previous one.
     */
    private suspend fun runRecorders(
        recorders: List<CaptureProvider>,
        accepts: (Demo) -> Boolean,
    ): Nothing =
        coroutineScope {
            val demoChannel = Channel<Demo>(Channel.RENDEZVOUS)
            recorders.forEach { recorder ->
                launch {
                    for (demo in demoChannel) {
                        val result = recorder.recordTracked(demo)
                        processRecording(result)
                        delay(config.cooldown)
                    }
                }
            }

            fetcher
                .fetch()
                .filter { accepts(it) && !isRecorded(it) }
                .collect { demoChannel.send(it) }

            error("The demo flow completed")
        }

    /**
     * Returns `true` if the cap time of this demo is at most [ServerConfig.longThreshold].
     */
    private fun Demo.isShort(): Boolean = capTime <= config.longThreshold

    /**
     * Stores the mp4 of [result] if it is done, and logs it otherwise.
     */
    private suspend fun processRecording(result: CaptureResult) {
        when (result) {
            is CaptureResult.Done -> {
                suspendCatching { store(result.demo, result.mp4) }
                    .onFailure { log.error(it) { "Could not store recording ${result.demo.capId}" } }
            }

            is CaptureResult.Failed -> {
                log.error(result.exception) {
                    "Giving up on recording ${result.demo.capId} after ${config.maxAttempts} attempts"
                }
            }

            is CaptureResult.Unresolvable -> {
                log.error(result.exception) {
                    "Giving up on recording ${result.demo.capId}, couldn't resolve required packages"
                }
            }
        }
    }

    /**
     * Returns the result of [recordRetrying], tracked in [jobs]. Logs [demo] before the recording starts, and again
     * with the time the recording took once it ends.
     */
    private suspend fun CaptureProvider.recordTracked(demo: Demo): CaptureResult {
        log.info { "Recording: $demo" }
        val (result, elapsed) = measureTimedValue { jobs.track(demo) { recordRetrying(demo, it) } }
        log.info { "Finished recording: $demo in ${elapsed.inWholeSeconds.seconds}" }
        return result
    }

    /**
     * Returns the result of the last attempt at recording [demo] on this recorder. Records again after each
     * [CaptureResult.Failed], up to [ServerConfig.maxAttempts] attempts, once the cooldown that follows the failed
     * attempt has passed. Each attempt and its progress are reported to [tracker].
     */
    private suspend fun CaptureProvider.recordRetrying(
        demo: Demo,
        tracker: RecordingJobs.Tracker,
    ): CaptureResult {
        var attempt = 1
        while (true) {
            tracker.startAttempt(attempt)
            val capture = record(demo, tracker::report)
            if (capture !is CaptureResult.Failed || attempt >= config.maxAttempts) return capture
            log.warn(capture.exception) { "Recording ${demo.capId} failed, attempt $attempt of ${config.maxAttempts}" }
            delay(config.cooldown)
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
