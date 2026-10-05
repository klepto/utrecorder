package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.server.statistics.StatisticsReport
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * [Endpoint] that reports the statistics of the server.
 *
 * The route is:
 * - `GET /api/statistics`: the [StatisticsReport.read] of [report], computed anew on each request. It takes no
 *   parameters, and waits until the queue has been built once.
 *
 * @property report The source of the statistics.
 */
class StatisticsEndpoint(
    private val report: StatisticsReport,
) : Endpoint {
    override fun install(route: Route) {
        route.get("/api/statistics") { call.respond(report.read()) }
    }
}
