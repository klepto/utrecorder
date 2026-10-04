package dev.klepto.utrecorder.server.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * [Endpoint] that serves the web site, and answers every path the other endpoints do not.
 *
 * The routes are:
 * - Any path under `/api/` that no other endpoint answers: `404` with a JSON body.
 * - `GET /index`: `404`, so the `index` of the resources is not served.
 * - Every other `GET`: the web site from the `web` classpath resources. A path that names no file of the site answers
 *   its `index.html`. Files under `assets/` are cached for a year; `index.html` is never cached without revalidation.
 *
 * Install it after every other endpoint.
 */
class WebEndpoint : Endpoint {
    override fun install(route: Route) {
        route.route("/api/{...}") { handle { call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found")) } }
        route.get("/index") { call.respond(HttpStatusCode.NotFound) }
        route.staticResources("/", "web") {
            default("index.html")
            modify { resource, call -> call.response.header(HttpHeaders.CacheControl, cacheControl(resource.path)) }
        }
    }

    private companion object {
        /**
         * Returns the `Cache-Control` header of the site resource at [path].
         */
        fun cacheControl(path: String): String =
            when {
                "/web/assets/" in path -> "public, max-age=31536000, immutable"
                else -> "no-cache"
            }
    }
}
