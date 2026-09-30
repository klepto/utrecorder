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
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
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
 * - `GET /demo/{capId}`: the mp4 of a [Recorded], [Uploaded] or [Archived] recording, with range support. If the
 *   recording is [Archived] and its mp4 is not in [ServerConfig.recordings], redirects to the download URL of [cache]
 *   instead, if [cache] names one. Every other request answers `404`.
 * - `GET /api/jobs`: every running recording, with its attempt and progress.
 * - `GET /api/queue`: the next 10 demos to be recorded, in the order they are taken.
 * - `GET /api/recordings?limit=&offset=`: one page of the [Recorded], [Uploaded] or [Archived] recordings, most
 *   recently recorded first. Recordings recorded at the same time are ordered by the time of their demo, then by
 *   [Demo.capId], both descending. A recording without [Recording.recordedAt] comes after every other one. The page
 *   skips `offset` recordings, `0` by default, and holds at most `limit` recordings, `10` by default and at most `50`.
 *   A `limit` or `offset` that is not a non-negative integer answers `400`.
 * - Any other path under `/api/`: `404` with a JSON body.
 * - Every other `GET`: the web site from the `web` classpath resources. A path that names no file of the site answers
 *   its `index.html`, and the `index` of the resources answers `404`. Files under `assets/` are cached for a year;
 *   `index.html` is never cached without revalidation.
 *
 * JavaScript, CSS, HTML and JSON responses are compressed.
 *
 * Every request of `/demo/{capId}` for a stored recording counts one view of it through [cache].
 *
 * @property repository The recordings to serve.
 * @property jobs The running recordings.
 * @property cache The cache that counts the views and names the download URL of an archived mp4.
 * @property config The recordings directory and the port.
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
        application.install(ContentNegotiation) { json() }
        application.install(Compression) {
            gzip { matchContentType(*COMPRESSED) }
            deflate { matchContentType(*COMPRESSED) }
        }
        application.routing {
            get("/demo/{capId}") { serveDemo(call.parameters["capId"].orEmpty()) }
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
     * Answers with the mp4 of the recording [capId], a redirect to its download URL, or `404`.
     */
    private suspend fun RoutingContext.serveDemo(capId: String) {
        val recording = cache.view(capId)
        if (recording == null || recording.status !in COMPLETED) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val capId = recording.demo.capId
        val mp4 = config.mp4(capId)
        val downloadUrl = cache.downloadUrl(capId)
        when {
            mp4.fileExists() -> call.respondFile(mp4.toFile())
            recording.status == Archived && downloadUrl != null -> call.respondRedirect(downloadUrl)
            else -> call.respond(HttpStatusCode.NotFound)
        }
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
     * Returns at most [limit] [Recorded], [Uploaded] or [Archived] recordings, most recently recorded first, after
     * skipping the first [offset] of them.
     */
    private suspend fun recordings(
        limit: Int,
        offset: Int,
    ): List<Recording> =
        repository
            .all()
            .filter { it.status in COMPLETED }
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

        fun RecordingJob.toResponse(): JobResponse = JobResponse(demo, attempt, progress?.state, progress?.progress, startedAt)
    }
}
