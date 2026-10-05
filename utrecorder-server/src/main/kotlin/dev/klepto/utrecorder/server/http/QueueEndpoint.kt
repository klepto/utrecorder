package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.server.recording.RecordingQueue
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * [Endpoint] that reports the order [queue] records the pending recordings in.
 *
 * The route is:
 * - `GET /api/queue`: every pending recording of [queue], in queue order, paged by [respondPage] and keyed by the cap id
 *   of its demo.
 *
 * @property queue The order the pending recordings are recorded in.
 */
class QueueEndpoint(
    private val queue: RecordingQueue,
) : Endpoint {
    override fun install(route: Route) {
        route.get("/api/queue") { respondPage(queue.get(Int.MAX_VALUE)) { it.demo.capId } }
    }
}
