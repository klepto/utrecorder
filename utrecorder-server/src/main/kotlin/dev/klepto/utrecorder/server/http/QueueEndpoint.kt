package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.server.recording.RecordingQueue
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * [Endpoint] that reports the order [queue] records the pending recordings in.
 *
 * The routes are:
 * - `GET /api/queue`: every pending recording of [queue], in queue order, paged by [respondPage] and keyed by the cap id
 *   of its demo.
 * - `GET /api/queue/next`: the cap id of the demo at the head of [queue], with the earliest time a recorder may take it
 *   as [RecordingQueue.next] reports it. If the queue is empty, answers `204 No Content` instead.
 *
 * @property queue The order the pending recordings are recorded in.
 */
class QueueEndpoint(
    private val queue: RecordingQueue,
) : Endpoint {
    override fun install(route: Route) {
        route.get("/api/queue") { respondPage(queue.get(Int.MAX_VALUE)) { it.demo.capId } }
        route.get("/api/queue/next") {
            when (val head = queue.next()) {
                null -> call.respond(HttpStatusCode.NoContent)
                else -> call.respond(NextResponse(head.recording.demo.capId, head.startsAt))
            }
        }
    }

    /**
     * The head of the queue, as `/api/queue/next` reports it.
     *
     * @property capId The cap id of the demo at the head of the queue.
     * @property startsAt The earliest time a recorder may take the head, or `null` while the backlog buffer holds it back.
     */
    @Serializable
    private data class NextResponse(
        val capId: String,
        val startsAt: Instant?,
    )
}
