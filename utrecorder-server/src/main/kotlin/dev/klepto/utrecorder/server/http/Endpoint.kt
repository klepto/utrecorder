package dev.klepto.utrecorder.server.http

import io.ktor.server.routing.Route

/**
 * A group of routes that [HttpService] serves.
 */
interface Endpoint {
    /**
     * Installs the routes of this endpoint on [route], the routing root of [HttpService].
     */
    fun install(route: Route)
}
