package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.routing.routing
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/**
 * [Service] that serves the routes of its [endpoints] over HTTP on [ServerConfig.port].
 *
 * The routes come from [endpoints], installed in list order on the routing root. Every `GET` route also answers
 * `HEAD`, with the headers of its `GET` answer and no body. A file answer supports `Range` requests. JavaScript, CSS,
 * HTML and JSON responses are compressed. Every response allows any origin to read it. The origin of a request
 * honours the `X-Forwarded-*` headers only if [ServerConfig.trustForwardedHeaders] is enabled.
 *
 * @property endpoints The endpoints whose routes are served.
 * @property config The port and whether the forwarded headers are trusted.
 */
class HttpService(
    private val endpoints: List<Endpoint>,
    private val config: ServerConfig = ServerConfig(),
) : Service {
    override suspend fun start() {
        val server = embeddedServer(CIO, port = config.port) { module(this) }
        server.startSuspend()
        log.info { "Listening on port ${config.port}" }
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { server.stopSuspend() }
        }
    }

    /**
     * Installs the plugins of this service on [application], then the routes of every endpoint in list order.
     */
    fun module(application: Application) {
        application.install(PartialContent)
        application.install(AutoHeadResponse)
        if (config.trustForwardedHeaders) application.install(XForwardedHeaders)
        application.install(ContentNegotiation) { json() }
        application.install(CORS) { anyHost() }
        application.install(Compression) {
            gzip { matchContentType(*COMPRESSED) }
            deflate { matchContentType(*COMPRESSED) }
        }
        application.routing {
            endpoints.forEach { it.install(this) }
        }
    }

    private companion object {
        val COMPRESSED =
            arrayOf(
                ContentType.Text.JavaScript,
                ContentType.Application.JavaScript,
                ContentType.Text.CSS,
                ContentType.Text.Html,
                ContentType.Application.Json,
            )

        val log = logger<HttpService>()
    }
}
