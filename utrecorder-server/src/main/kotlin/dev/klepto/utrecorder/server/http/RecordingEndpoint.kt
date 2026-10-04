package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.io.fileExists
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get

/**
 * [Endpoint] that answers the recordings of [repository] as JSON.
 *
 * The routes are:
 * - `GET /api/recording/{capId}`: the recording of the cap `capId`, whatever its status. A cap without a recording
 *   answers `404` with a JSON body.
 * - `GET /api/recordings?status=` plus the paging parameters of [respondPage]: one page of the [Recorded], [Uploaded] or
 *   [Archived] recordings, plus every [Outdated] recording whose mp4 still exists, most recently recorded first, keyed
 *   by [Demo.capId]. Recordings recorded at the same time are ordered by the time of their demo, then by
 *   [Demo.capId], both descending. A recording without [Recording.recordedAt] comes after every other one. If `status`
 *   is given, the list holds every recording of the statuses it names instead, separated by commas and in any case,
 *   such as `failed,unresolvable`, in the same order. A `status` that names a status that does not exist answers `400`
 *   before the paging is read.
 *
 * @property repository The recordings to answer.
 * @property config The recordings directory, which decides whether an [Outdated] recording is listed.
 */
class RecordingEndpoint(
    private val repository: RecordingRepository,
    private val config: ServerConfig = ServerConfig(),
) : Endpoint {
    override fun install(route: Route) {
        route.get("/api/recording/{capId}") { serveRecording(call.parameters["capId"].orEmpty()) }
        route.get("/api/recordings") { serveRecordings() }
    }

    /**
     * Answers with the recording [capId], whatever its status. If there is none, answers `404` instead.
     */
    private suspend fun RoutingContext.serveRecording(capId: String) {
        when (val recording = repository[capId]) {
            null -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
            else -> call.respond(recording)
        }
    }

    /**
     * Answers with the page of recordings that the paging parameters name, keyed by cap id, or `400` for a `status`
     * that names no [RecordingStatus] or a paging parameter that [respondPage] rejects. The `status` check comes first.
     */
    private suspend fun RoutingContext.serveRecordings() {
        val status = call.parameters["status"]
        val statuses = status?.let { statuses(it) }
        if (status != null && statuses == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Status must be recording statuses separated by commas"))
            return
        }
        val recordings =
            repository
                .all()
                .filter { if (statuses == null) isListed(it) else it.status in statuses }
                .sortedWith(NEWEST_RECORDED)
        respondPage(recordings) { it.demo.capId }
    }

    /**
     * Returns `true` if [recording] is [Recorded], [Uploaded] or [Archived], or [Outdated] while its mp4 still exists.
     */
    private suspend fun isListed(recording: Recording): Boolean =
        recording.status.isCompleted || (recording.status == Outdated && config.mp4(recording.demo.capId).fileExists())

    private companion object {
        val NEWEST_RECORDED: Comparator<Recording> =
            compareByDescending<Recording> { it.recordedAt }
                .thenByDescending { it.demo.timestamp }
                .thenByDescending { it.demo.capId }

        /**
         * Returns the statuses that [value] names, separated by commas, in any case. If a name is not a
         * [RecordingStatus], returns `null` instead.
         */
        fun statuses(value: String): Set<RecordingStatus>? =
            value
                .split(',')
                .map { name -> RecordingStatus.entries.find { it.name.equals(name, ignoreCase = true) } ?: return null }
                .toSet()
    }
}
