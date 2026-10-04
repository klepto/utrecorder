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
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.autohead.AutoHeadResponse
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
 * - `GET /thumb/{capId}`: the thumbnail of a recording `/mp4/{capId}` would serve, if it is in
 *   [ServerConfig.thumbnails]. Every other request answers `404`.
 * - `GET /view/{capId}`: an HTML page that plays `/mp4/{capId}` and carries OpenGraph video tags for link previews: the
 *   title of the cap as its site name and no title of its own, the size of the first, largest tier of `record.tiers` as
 *   the size of the video and the image, `/thumb/{capId}` as its image, and absolute URLs built from the origin of the
 *   request. The origin honours the `X-Forwarded-*` headers only if [ServerConfig.trustForwardedHeaders] is enabled.
 *   Answers `404` wherever `/mp4/{capId}` answers `404`.
 * - `GET /api/jobs`: every running recording, with its attempt and progress.
 * - `GET /api/queue`: the demos of the first 10 recordings of [queue], in queue order.
 * - `GET /api/recording/{capId}`: the recording of the cap `capId`, whatever its status. A cap without a recording
 *   answers `404` with a JSON body.
 * - `GET /api/recordings?limit=&offset=&status=`: one page of the [Recorded], [Uploaded] or [Archived] recordings, plus
 *   every [Outdated] recording whose mp4 still exists, most recently recorded first. Recordings recorded at the same
 *   time are ordered by the time of their demo, then by [Demo.capId], both descending. A recording without
 *   [Recording.recordedAt] comes after every other one. The page
 *   skips `offset` recordings, `0` by default, and holds at most `limit` recordings, `10` by default and at most `50`.
 *   A `limit` or `offset` that is not a non-negative integer answers `400`. If `status` is given, the page holds every
 *   recording of the statuses it names instead, separated by commas and in any case, such as `failed,unresolvable`,
 *   and `limit` has neither a default nor a maximum. A `status` that names a status that does not exist answers `400`.
 * - Any other path under `/api/`: `404` with a JSON body.
 * - Every other `GET`: the web site from the `web` classpath resources. A path that names no file of the site answers
 *   its `index.html`, and the `index` of the resources answers `404`. Files under `assets/` are cached for a year;
 *   `index.html` is never cached without revalidation.
 *
 * Every `GET` route also answers `HEAD`, with the headers of its `GET` answer and no body. JavaScript, CSS, HTML and
 * JSON responses are compressed.
 *
 * A `GET` of `/mp4/{capId}` for a stored recording counts one view of it through [cache], unless it continues a
 * playback: a request with a `Range` header other than `bytes=0-` counts none. A `HEAD` request and a request of
 * `/thumb/{capId}` or `/view/{capId}` count none.
 *
 * @property repository The recordings to serve.
 * @property queue The order the pending recordings are recorded in.
 * @property jobs The running recordings.
 * @property cache The cache that counts the views and names the download URL of an archived mp4.
 * @property config The recordings and thumbnails directories, the port, whether the forwarded headers are trusted
 * and the size of the first, largest tier of `record.tiers`.
 */
class HttpService(
    private val repository: RecordingRepository,
    private val queue: RecordingQueue,
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
        application.install(AutoHeadResponse)
        if (config.trustForwardedHeaders) application.install(XForwardedHeaders)
        application.install(ContentNegotiation) { json() }
        application.install(Compression) {
            gzip { matchContentType(*COMPRESSED) }
            deflate { matchContentType(*COMPRESSED) }
        }
        application.routing {
            get("/mp4/{capId}") { serveMp4(call.parameters["capId"].orEmpty()) }
            get("/thumb/{capId}") { serveThumbnail(call.parameters["capId"].orEmpty()) }
            get("/view/{capId}") { serveView(call.parameters["capId"].orEmpty()) }
            get("/api/jobs") { call.respond(jobs.jobs.first().map { it.toResponse() }) }
            get("/api/queue") { call.respond(queue.get(LIMIT).map { it.demo }) }
            get("/api/recording/{capId}") { serveRecording(call.parameters["capId"].orEmpty()) }
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
     * Answers with the mp4 of the recording [requestedCapId], a redirect to its download URL, or `404`. An [Outdated] recording
     * is never redirected: once its mp4 is gone, it answers `404`.
     */
    private suspend fun RoutingContext.serveMp4(requestedCapId: String) {
        val recording = cache.view(requestedCapId, counted = call.request.local.method == HttpMethod.Get && isFromStart())
        if (recording == null || !recording.status.isServable) {
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
     * Answers with the thumbnail of the recording [capId], or `404` if the recording is not served or has no
     * thumbnail.
     */
    private suspend fun RoutingContext.serveThumbnail(capId: String) {
        val thumbnail = repository[capId]?.takeIf { it.status.isServable }?.let { config.thumbnail(it.demo.capId) }
        when {
            thumbnail != null && thumbnail.fileExists() -> call.respondFile(thumbnail.toFile())
            else -> call.respond(HttpStatusCode.NotFound)
        }
    }

    /**
     * Answers with the page that plays the recording [requestedCapId] and describes it to link previews, or `404` wherever
     * [serveMp4] answers `404`.
     */
    private suspend fun RoutingContext.serveView(requestedCapId: String) {
        val recording = repository[requestedCapId]
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
                thumbnailUrl = call.url { path("thumb", capId) },
            )
        call.respondText(page, ContentType.Text.Html)
    }

    /**
     * Returns whether [serveMp4] answers the recording with its mp4 or a redirect to it.
     */
    private suspend fun isPlayable(recording: Recording): Boolean {
        val capId = recording.demo.capId
        return when {
            !recording.status.isServable -> false
            config.mp4(capId).fileExists() -> true
            else -> recording.status == Archived && cache.downloadUrl(recording) != null
        }
    }

    /**
     * Returns the HTML page that plays the mp4 at [mp4Url] and carries the OpenGraph video tags of [demo], with
     * [pageUrl] as its canonical URL and the thumbnail at [thumbnailUrl] as its image.
     */
    private fun viewPage(
        demo: Demo,
        pageUrl: String,
        mp4Url: String,
        thumbnailUrl: String,
    ): String {
        val title = demo.title().escapeHtml()
        val mp4 = mp4Url.escapeHtml()
        val tier =
            config.recorder.video.tiers
                .first()
        return """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <meta property="og:site_name" content="$title" />
                <meta property="og:type" content="video.other" />
                <meta property="og:url" content="${pageUrl.escapeHtml()}" />
                <meta property="og:video" content="$mp4" />
                <meta property="og:video:type" content="video/mp4" />
                <meta property="og:video:width" content="${tier.width}" />
                <meta property="og:video:height" content="${tier.height}" />
                <meta property="og:image" content="${thumbnailUrl.escapeHtml()}" />
                <meta property="og:image:type" content="image/jpeg" />
                <meta property="og:image:width" content="${tier.width}" />
                <meta property="og:image:height" content="${tier.height}" />
              </head>
              <body style="margin: 0; background: #000">
                <video src="$mp4" controls autoplay playsinline style="display: block; width: 100vw; height: 100vh"></video>
              </body>
            </html>
            """.trimIndent()
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
     * Answers with the page of recordings that the `limit` and `offset` parameters name, or `400` for a parameter that
     * is not a non-negative integer.
     */
    private suspend fun RoutingContext.serveRecordings() {
        val status = call.parameters["status"]
        val statuses = status?.let { statuses(it) }
        val defaultLimit =
            when (status) {
                null -> LIMIT
                else -> Int.MAX_VALUE
            }
        val limit = count(call.parameters["limit"], defaultLimit)
        val offset = count(call.parameters["offset"], 0)
        if (limit == null || offset == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Limit and offset must be non-negative integers"))
            return
        }
        if (status != null && statuses == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Status must be recording statuses separated by commas"))
            return
        }
        val recordings =
            when (statuses) {
                null -> recordings(limit.coerceAtMost(MAX_LIMIT), offset) { isListed(it) }
                else -> recordings(limit, offset) { it.status in statuses }
            }
        call.respond(recordings)
    }

    /**
     * Returns at most [limit] of the recordings that [filter] accepts, most recently recorded first, after skipping the
     * first [offset] of them.
     */
    private suspend fun recordings(
        limit: Int,
        offset: Int,
        filter: suspend (Recording) -> Boolean,
    ): List<Recording> =
        repository
            .all()
            .filter { filter(it) }
            .sortedWith(NEWEST_RECORDED)
            .drop(offset)
            .take(limit)

    /**
     * Returns `true` if [recording] is [Recorded], [Uploaded] or [Archived], or [Outdated] while its mp4 still exists.
     */
    private suspend fun isListed(recording: Recording): Boolean =
        recording.status.isCompleted || (recording.status == Outdated && config.mp4(recording.demo.capId).fileExists())

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

        val NEWEST_RECORDED: Comparator<Recording> =
            compareByDescending<Recording> { it.recordedAt }
                .thenByDescending { it.demo.timestamp }
                .thenByDescending { it.demo.capId }

        val log = logger<HttpService>()

        /**
         * `true` if a recording with this status is served: it is [completed][RecordingStatus.isCompleted] or [Outdated].
         */
        val RecordingStatus.isServable: Boolean
            get() = isCompleted || this == Outdated

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
         * Returns the statuses that [value] names, separated by commas, in any case. If a name is not a
         * [RecordingStatus], returns `null` instead.
         */
        fun statuses(value: String): Set<RecordingStatus>? =
            value
                .split(',')
                .map { name -> RecordingStatus.entries.find { it.name.equals(name, ignoreCase = true) } ?: return null }
                .toSet()

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
