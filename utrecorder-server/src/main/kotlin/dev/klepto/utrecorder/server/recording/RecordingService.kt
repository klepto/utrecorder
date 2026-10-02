package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.fileExists
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimedValue
import kotlin.time.measureTimedValue

/**
 * [Service] that records the demos of [demos] into [ServerConfig.recordings], in the order [repository] gives them.
 *
 * Only the fastest demo of each player on each map is recorded. Players are told apart by [Demo.userId]. Of two
 * demos with the same cap time, the one added first is recorded. A demo without a [Demo.userId] is never recorded.
 * Whenever [demos] changes, and after each capture ends, a recording of each such demo is stored in [repository].
 *
 * A [Recorded], [Uploaded] or [Archived] recording stays chosen until a faster recording of the same player on the same
 * map is [Recorded], [Uploaded] or [Archived] as well; it is set to [Outdated] then. Every recording that has not been
 * recorded and whose demo is no longer chosen is removed. A [RecordingStatus.Recording] one is left alone until its
 * capture ends.
 *
 * On start, every [RecordingStatus.Recording], [Failed] or [Unresolvable] recording of [repository] is set to [Pending]
 * again, before any demo is taken. Once every [ServerConfig.recordingRetryInterval] after that, every [Failed] or
 * [Unresolvable] recording is set to [Pending] again.
 *
 * Each of [recorders] takes its demos from [repository] on its own, and records one demo at a time. It waits
 * [ServerConfig.recordingCooldown] after each attempt before it records again. The cooldown after the last attempt starts
 * once the recording is stored. A failed demo is recorded again on the same recorder, up to
 * [ServerConfig.recordingMaxAttempts] attempts. A demo that names a package no source holds is given up on at once. A demo
 * whose mp4 is already stored is set to [Recorded] without recording it. Each recording is tracked in [jobs] from its
 * first attempt until its last one ends, and its outcome is set on [repository].
 *
 * @property demos The demos to choose from.
 * @property repository The recordings of the chosen demos, and the queue they are taken from.
 * @property recorders The recorders that take the demos.
 * @property config The recordings directory, the attempts, the cooldown and the retry interval.
 * @property jobs The list the running recordings are tracked in.
 * @throws IllegalArgumentException If [recorders] is empty.
 */
class RecordingService(
    private val demos: DemoRepository,
    private val repository: RecordingRepository,
    private val recorders: List<RecorderProvider> = listOf(DefaultRecorderProvider()),
    private val config: ServerConfig = ServerConfig(),
    private val jobs: RecordingJobs = RecordingJobs(),
) : Service {
    private val queueing = Mutex()

    init {
        require(recorders.isNotEmpty()) { "Recorders must not be empty" }
    }

    override suspend fun start(): Unit =
        coroutineScope {
            requeue()
            launch { demos.changes.collect { queueFastest() } }
            launch { retryLoop() }
            recorders.forEach { launch { it.recordQueued() } }
            awaitCancellation()
        }

    /**
     * Sets every [RecordingStatus.Recording], [Failed] or [Unresolvable] recording of [repository] to [Pending].
     */
    private suspend fun requeue() {
        repository.updateAll { if (it.status in REQUEUED) it.copy(status = Pending) else it }
    }

    /**
     * Runs [retry] once every [ServerConfig.recordingRetryInterval].
     */
    private suspend fun retryLoop() {
        while (isRunning()) {
            delay(config.recordingRetryInterval)
            retry()
        }
    }

    /**
     * Sets every [Failed] or [Unresolvable] recording of [repository] to [Pending].
     */
    private suspend fun retry() {
        repository.updateAll { if (it.status in GIVEN_UP) it.copy(status = Pending) else it }
    }

    /**
     * Stores a recording of the fastest demo of each player on each map in [repository]. Chooses those demos, and the
     * fastest [COMPLETED] recording of each player on each map. Removes every recording that has not been recorded and
     * whose demo is not chosen, and sets every [COMPLETED] recording whose demo is not chosen to [Outdated].
     */
    private suspend fun queueFastest(): Unit =
        queueing.withLock {
            val fastest = fastest(demos.all())
            repository += fastest
            val completed = repository.all().filter { it.status in COMPLETED }
            val chosen = (fastest + fastest(completed.map { it.demo })).mapTo(HashSet()) { it.capId }
            val beaten = completed.map { it.demo.capId }.filterTo(HashSet()) { it !in chosen }
            repository.retainUnrecorded { it in chosen }
            repository.updateAll { if (it.demo.capId in beaten && it.status in COMPLETED) it.copy(status = Outdated) else it }
        }

    /**
     * Records the demos of [repository] on this recorder, one at a time.
     */
    private suspend fun RecorderProvider.recordQueued() {
        while (isRunning()) {
            val demo = repository.take()
            if (config.mp4(demo.capId).fileExists()) {
                repository.finish(demo.capId, Recorded)
                queueFastest()
                continue
            }
            val (result, processingTime) = recordTracked(demo)
            processRecording(demo, result, processingTime)
            queueFastest()
            delay(config.recordingCooldown)
        }
    }

    /**
     * Stores the mp4 of [result] if it is done, and logs it otherwise. Sets the outcome for [demo] on [repository]. A
     * stored mp4 is recorded in [processingTime].
     */
    private suspend fun processRecording(
        demo: Demo,
        result: RecorderResult,
        processingTime: Duration,
    ) {
        val capId = demo.capId
        when (result) {
            is RecorderResult.Done -> {
                suspendCatching { store(demo, result.mp4) }
                    .onSuccess { repository.recorded(capId, processingTime) }
                    .onFailure {
                        log.error(it) { "Could not store recording $capId" }
                        repository.finish(capId, Failed, it.toString())
                    }
            }

            is RecorderResult.Failed -> {
                log.error(result.exception) { "Giving up on recording $capId after ${config.recordingMaxAttempts} attempts" }
                repository.finish(capId, Failed, result.exception.toString())
            }

            is RecorderResult.Unresolvable -> {
                log.error(result.exception) { "Giving up on recording $capId, couldn't resolve required packages" }
                repository.finish(capId, Unresolvable, result.exception.toString())
            }
        }
    }

    /**
     * Returns the result of [recordRetrying], tracked in [jobs]. Logs [demo] before the recording starts, and again
     * with the time the recording took once it ends.
     */
    private suspend fun RecorderProvider.recordTracked(demo: Demo): TimedValue<RecorderResult> {
        log.info { "Recording: $demo" }
        val (result, elapsed) = measureTimedValue { jobs.track(demo) { recordRetrying(demo, it) } }
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
     * Moves [recordingPath] to the recorded file of [demo], then deletes the workspace that held it.
     */
    private suspend fun store(
        demo: Demo,
        recordingPath: Path,
    ) {
        recordingPath.move(config.mp4(demo.capId))
        recordingPath.parent
            .deleteCatching()
            .onFailure { log.warn(it) { "Could not delete the workspace ${recordingPath.parent}" } }
    }

    private companion object {
        val GIVEN_UP = setOf(Failed, Unresolvable)

        val REQUEUED = GIVEN_UP + RecordingStatus.Recording

        val COMPLETED = setOf(Recorded, Uploaded, Archived)

        val FASTEST_FIRST: Comparator<Demo> =
            compareBy<Demo> { it.capTime }
                .thenBy { it.timestamp }

        val log = logger<RecordingService>()

        /**
         * Returns the fastest of [demos] for each player on each map, oldest first. A demo without a [Demo.userId] is
         * never returned.
         */
        fun fastest(demos: List<Demo>): List<Demo> =
            demos
                .filter { it.userId != null }
                .groupBy { it.userId to it.map }
                .values
                .map { it.minWith(FASTEST_FIRST) }
                .sortedBy { it.timestamp }
    }
}
