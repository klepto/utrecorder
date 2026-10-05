package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingJob
import dev.klepto.utrecorder.server.recording.RecordingJobs
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * [Endpoint] that reports the running recordings of [jobs].
 *
 * The route is:
 * - `GET /api/jobs`: the running recordings, with their attempt and progress, in the order they started, paged through
 *   [respondPage] by `offset`, `limit`, `before` and `after`. A page holds at most 100 jobs, and each job is keyed by
 *   the `capId` of the demo of its recording.
 *
 * @property jobs The running recordings.
 */
class JobEndpoint(
    private val jobs: RecordingJobs,
) : Endpoint {
    override fun install(route: Route) {
        route.get("/api/jobs") { respondPage(jobs.jobs.first().map { it.toResponse() }) { it.recording.demo.capId } }
    }

    /**
     * One running recording, as `/api/jobs` lists it.
     *
     * @property recording The recording being captured, as it was when the capture was claimed.
     * @property attempt The number of the current attempt, starting at `1`.
     * @property stage The stage of the current attempt, or `null` if it has not reported yet.
     * @property progress How much of [stage] is done, from `0.0` to `1.0`, or `null` if it has not reported yet.
     * @property startedAt The time the first attempt started.
     */
    @Serializable
    private data class JobResponse(
        val recording: Recording,
        val attempt: Int,
        val stage: RecorderStage?,
        val progress: Double?,
        val startedAt: Instant,
    )

    private companion object {
        fun RecordingJob.toResponse(): JobResponse = JobResponse(recording, attempt, progress?.state, progress?.progress, startedAt)
    }
}
