package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.io.fileExists
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.cache.CacheService
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingJob
import dev.klepto.utrecorder.server.recording.RecordingJobs
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.util.url
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * [Service] that serves the recordings of [repository] and the state of the server over HTTP on [ServerConfig.port].
 *
 * The routes are:
 * - `GET /mp4/{capId}`: the mp4 of a [Recorded], [Uploaded] or [Archived] recording, with range support. If the
 *   recording is [Archived] and its mp4 is not in [ServerConfig.recordings], redirects to the download URL of [cache]
 *   instead, if [cache] names one. The mp4 of an [Outdated] recording is served the same way, but never redirected;
 *   once its mp4 is gone, the recording answers `404`. Every other request answers `404`.
 * - `GET /view/{capId}`: an HTML page that plays `/mp4/{capId}` and carries OpenGraph video tags for link previews: the
 *   title of the cap, the video size of [ServerConfig.recorder], and absolute URLs built from the origin of the
 *   request. The origin honours the `X-Forwarded-*` headers only if [ServerConfig.trustForwardedHeaders] is enabled.
 *   Answers `404` wherever `/mp4/{capId}` answers `404`.
 * - `GET /api/jobs`: every running recording, with its attempt and progress.
 * - `GET /api/queue`: the next 10 demos to be recorded, in the order they are taken.
 * - `GET /api/recordings?limit=&offset=`: one page of the [Recorded], [Uploaded] or [Archived] recordings, plus every
 *   [Outdated] recording whose mp4 still exists, most recently recorded first. Recordings recorded at the same time
 *   are ordered by the time of their demo, then by [Demo.capId], both descending. A recording without
 *   [Recording.recordedAt] comes after every other one. The page
 *   skips `offset` recordings, `0` by default, and holds at most `limit` recordings, `10` by default and at most `50`.
 *   A `limit` or `offset` that is not a non-negative integer answers `400`.
 * - Any other path under `/api/`: `404` with a JSON body.
 * - Every other `GET`: the web site from the `web` classpath resources. A path that names no file of the site answers
 *   its `index.html`, and the `index` of the resources answers `404`. Files under `assets/` are cached for a year;
 *   `index.html` is never cached without revalidation.
 *
 * JavaScript, CSS, HTML and JSON responses are compressed.
 *
 * A request of `/mp4/{capId}` for a stored recording counts one view of it through [cache], unless it continues a
 * playback: a request with a `Range` header other than `bytes=0-` counts none. A request of `/view/{capId}` counts
 * none.
 *
 * @property repository The recordings to serve.
 * @property jobs The running recordings.
 * @property cache The cache that counts the views and names the download URL of an archived mp4.
 * @property config The recordings directory, the port, whether the forwarded headers are trusted and the size of the
 * recorded videos.
 */
class HttpService(
    private val repository: RecordingRepository,
    private val jobs: RecordingJobs,
    private val cache: CacheService,
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
     * Installs the plugins and the routes of this service on [application].
     */
    fun module(application: Application) {
        application.install(PartialContent)
        if (config.trustForwardedHeaders) application.install(XForwardedHeaders)
        application.install(ContentNegotiation) { json() }
        application.install(Compression) {
            gzip { matchContentType(*COMPRESSED) }
            deflate { matchContentType(*COMPRESSED) }
        }
        application.routing {
            get("/mp4/{capId}") { serveMp4(call.parameters["capId"].orEmpty()) }
            get("/view/{capId}") { serveView(call.parameters["capId"].orEmpty()) }
            get("/api/jobs") { call.respond(jobs.jobs.first().map { it.toResponse() }) }
            get("/api/queue") { call.respond(repository.pending(LIMIT)) }
            get("/api/recordings") { serveRecordings() }
            route("/api/{...}") { handle { call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found")) } }
            get("/index") { call.respond(HttpStatusCode.NotFound) }
            staticResources("/", "web") {
                default("index.html")
                modify { resource, call -> call.response.header(HttpHeaders.CacheControl, cacheControl(resource.path)) }
            }
        }
    }

    /**
     * Answers with the mp4 of the recording [capId], a redirect to its download URL, or `404`. An [Outdated] recording
     * is never redirected: once its mp4 is gone, it answers `404`.
     */
    private suspend fun RoutingContext.serveMp4(capId: String) {
        val recording = cache.view(capId, counted = isFromStart())
        if (recording == null || recording.status !in SERVABLE) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val capId = recording.demo.capId
        val mp4 = config.mp4(capId)
        val downloadUrl = cache.downloadUrl(recording)
        when {
            mp4.fileExists() -> call.respondFile(mp4.toFile())
            recording.status == Archived && downloadUrl != null -> call.respondRedirect(downloadUrl)
            else -> call.respond(HttpStatusCode.NotFound)
        }
    }

    /**
     * Returns `true` if the request asks for the mp4 from its start: it has no `Range` header, or the range `bytes=0-`.
     */
    private fun RoutingContext.isFromStart(): Boolean =
        when (call.request.headers[HttpHeaders.Range]) {
            null, "bytes=0-" -> true
            else -> false
        }

    /**
     * Answers with the page that plays the recording [capId] and describes it to link previews, or `404` wherever
     * [serveMp4] answers `404`.
     */
    private suspend fun RoutingContext.serveView(capId: String) {
        val recording = repository[capId]
        if (recording == null || !isPlayable(recording)) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val capId = recording.demo.capId
        val page =
            viewPage(
                demo = recording.demo,
                pageUrl = call.url { path("view", capId) },
                mp4Url = call.url { path("mp4", capId) },
            )
        call.respondText(page, ContentType.Text.Html)
    }

    /**
     * Returns whether [serveMp4] answers the recording with its mp4 or a redirect to it.
     */
    private suspend fun isPlayable(recording: Recording): Boolean {
        val capId = recording.demo.capId
        return when {
            recording.status !in SERVABLE -> false
            config.mp4(capId).fileExists() -> true
            else -> recording.status == Archived && cache.downloadUrl(recording) != null
        }
    }

    /**
     * Returns the HTML page that plays the mp4 at [mp4Url] and carries the OpenGraph video tags of [demo], with
     * [pageUrl] as its canonical URL.
     */
    private fun viewPage(
        demo: Demo,
        pageUrl: String,
        mp4Url: String,
    ): String {
        val title = "${demo.map} - ${demo.formatCapTime()} by ${demo.player}".escapeHtml()
        val mp4 = mp4Url.escapeHtml()
        return """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>$title</title>
                <meta property="og:site_name" content="utrecorder" />
                <meta property="og:type" content="video.other" />
                <meta property="og:title" content="$title" />
                <meta property="og:url" content="${pageUrl.escapeHtml()}" />
                <meta property="og:video" content="$mp4" />
                <meta property="og:video:type" content="video/mp4" />
                <meta property="og:video:width" content="${config.recorder.video.width}" />
                <meta property="og:video:height" content="${config.recorder.video.height}" />
              </head>
              <body style="margin: 0; background: #000">
                <video src="$mp4" controls autoplay playsinline style="display: block; width: 100vw; height: 100vh"></video>
              </body>
            </html>
            """.trimIndent()
    }

    /**
     * Answers with the page of recordings that the `limit` and `offset` parameters name, or `400` for a parameter that
     * is not a non-negative integer.
     */
    private suspend fun RoutingContext.serveRecordings() {
        val limit = count(call.parameters["limit"], LIMIT)
        val offset = count(call.parameters["offset"], 0)
        if (limit == null || offset == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Limit and offset must be non-negative integers"))
            return
        }
        call.respond(recordings(limit.coerceAtMost(MAX_LIMIT), offset))
    }

    /**
     * Returns at most [limit] [Recorded], [Uploaded] or [Archived] recordings, plus every [Outdated] recording whose
     * mp4 still exists, most recently recorded first, after skipping the first [offset] of them.
     */
    private suspend fun recordings(
        limit: Int,
        offset: Int,
    ): List<Recording> =
        repository
            .all()
            .filter { it.status in COMPLETED || (it.status == Outdated && config.mp4(it.demo.capId).fileExists()) }
            .sortedWith(NEWEST_RECORDED)
            .drop(offset)
            .take(limit)

    /**
     * One running recording, as `/api/jobs` lists it.
     *
     * @property demo The demo being recorded.
     * @property attempt The number of the current attempt, starting at `1`.
     * @property stage The stage of the current attempt, or `null` if it has not reported yet.
     * @property progress How much of [stage] is done, from `0.0` to `1.0`, or `null` if it has not reported yet.
     * @property startedAt The time the first attempt started.
     */
    @Serializable
    private data class JobResponse(
        val demo: Demo,
        val attempt: Int,
        val stage: RecorderStage?,
        val progress: Double?,
        val startedAt: Instant,
    )

    /**
     * Body of an error response.
     *
     * @property error What went wrong.
     */
    @Serializable
    private data class ErrorResponse(
        val error: String,
    )

    private companion object {
        const val LIMIT = 10

        const val MAX_LIMIT = 50

        val COMPRESSED =
            arrayOf(
                ContentType.Text.JavaScript,
                ContentType.Application.JavaScript,
                ContentType.Text.CSS,
                ContentType.Text.Html,
                ContentType.Application.Json,
            )

        val COMPLETED = setOf(Recorded, Uploaded, Archived)

        val SERVABLE = COMPLETED + Outdated

        val NEWEST_RECORDED: Comparator<Recording> =
            compareByDescending<Recording> { it.recordedAt }
                .thenByDescending { it.demo.timestamp }
                .thenByDescending { it.demo.capId }

        val log = logger<HttpService>()

        /**
         * Returns [value] as a non-negative integer, or `null` if it is not one. If [value] is `null`, returns [default]
         * instead.
         */
        fun count(
            value: String?,
            default: Int,
        ): Int? =
            when (value) {
                null -> default
                else -> value.toIntOrNull()?.takeIf { it >= 0 }
            }

        /**
         * Returns the `Cache-Control` header of the site resource at [path].
         */
        fun cacheControl(path: String): String =
            when {
                "/web/assets/" in path -> "public, max-age=31536000, immutable"
                else -> "no-cache"
            }

        /**
         * Returns this text with the characters that HTML gives a meaning escaped, so it reads as text in an element
         * or an attribute value.
         */
        fun String.escapeHtml(): String =
            replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")

        fun RecordingJob.toResponse(): JobResponse = JobResponse(demo, attempt, progress?.state, progress?.progress, startedAt)
    }
}
