package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.server.demo.Demo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * List of the recordings that are running, each added by [track] and removed once its block ends.
 */
class RecordingJobs {
    private val state = MutableStateFlow<Map<Tracker, RecordingJob>>(emptyMap())

    /**
     * The running recordings. Emits the current list when collected, then again on every change.
     */
    val jobs: Flow<List<RecordingJob>> = state.map { it.values.toList() }

    /**
     * Returns the result of [block], and lists a job recording [demo] at its first attempt until [block] returns or
     * throws. [block] receives the [Tracker] that updates the job.
     */
    suspend fun <T> track(
        demo: Demo,
        block: suspend (Tracker) -> T,
    ): T {
        val tracker = JobTracker()
        state.update { it + (tracker to RecordingJob(demo, 1, null, Clock.System.now())) }
        return try {
            block(tracker)
        } finally {
            state.update { it - tracker }
        }
    }

    /**
     * Updater of the job of one [track] block. Once the block ends, every update is ignored.
     */
    interface Tracker {
        /**
         * Sets the job to [attempt], without progress.
         */
        fun startAttempt(attempt: Int)

        /**
         * Sets [progress] on the job.
         */
        fun report(progress: Progress<RecorderStage>)
    }

    private inner class JobTracker : Tracker {
        override fun startAttempt(attempt: Int) = update { it.copy(attempt = attempt, progress = null) }

        override fun report(progress: Progress<RecorderStage>) = update { it.copy(progress = progress) }

        /**
         * Replaces the job of this tracker with the result of [transform]. If the job is not listed, does nothing.
         */
        private fun update(transform: (RecordingJob) -> RecordingJob) {
            state.update { jobs ->
                val job = jobs[this] ?: return@update jobs
                jobs + (this to transform(job))
            }
        }
    }
}

/**
 * Recording of one demo that a recorder is running.
 *
 * @property demo The demo being recorded.
 * @property attempt The number of the current attempt, starting at `1`.
 * @property progress The last progress report of the current attempt, or `null` if it has not reported yet.
 * @property startedAt The time the first attempt started.
 */
data class RecordingJob(
    val demo: Demo,
    val attempt: Int,
    val progress: Progress<RecorderStage>?,
    val startedAt: Instant,
)
